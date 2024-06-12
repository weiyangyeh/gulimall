import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.beans.factory.annotation.Autowired;
import org.mybatis.spring.annotation.MapperScan;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

@SpringBootApplication
@EnableScheduling
@MapperScan("com.example.demo.mapper")
public class DataCleanerApplication implements CommandLineRunner {

    @Autowired
    private BankMapper bankMapper;

    @Autowired
    private AgentMapper agentMapper;

    @Autowired
    private ExecutorService executorService;

    public static void main(String[] args) {
        SpringApplication.run(DataCleanerApplication.class, args);
    }

    @Override
    public void run(String... args) {
        // 可以在這裡添加初始化邏輯
    }

    // 定期排程，每天執行一次
    @Scheduled(fixedRate = 86400000)
    public void cleanData() {
        cleanBankData();
        cleanAgentData();
    }

    private void cleanBankData() {
        int pageSize = 100; // 每批次處理的記錄數量
        int offset = 0;
        List<Bank> banks;

        // 處理 XY網點
        do {
            banks = bankMapper.selectXYBanks("%CHL%", offset, pageSize);
            for (Bank bank : banks) {
                executorService.submit(new BankCleanerTask(bank, bankMapper, "XY"));
            }
            offset += pageSize;
        } while (!banks.isEmpty());

        // 重置 offset
        offset = 0;

        // 處理 CH銀行營業地址
        do {
            banks = bankMapper.selectCHBanks("%CHL%", offset, pageSize);
            for (Bank bank : banks) {
                executorService.submit(new BankCleanerTask(bank, bankMapper, "CH"));
            }
            offset += pageSize;
        } while (!banks.isEmpty());
    }

    private void cleanAgentData() {
        int pageSize = 100; // 每批次處理的記錄數量
        int offset = 0;
        List<Agent> agents;

        // 處理 XY業務員資料
        do {
            agents = agentMapper.selectXYAgents("%CHL%", offset, pageSize);
            for (Agent agent : agents) {
                executorService.submit(new AgentCleanerTask(agent, agentMapper, "XY"));
            }
            offset += pageSize;
        } while (!agents.isEmpty());

        // 重置 offset
        offset = 0;

        // 處理 CH業務員資料
        do {
            agents = agentMapper.selectCHAgents("%CHL%", offset, pageSize);
            for (Agent agent : agents) {
                executorService.submit(new AgentCleanerTask(agent, agentMapper, "CH"));
            }
            offset += pageSize;
        } while (!agents.isEmpty());
    }

    // 清理銀行數據的公共任務
    static class BankCleanerTask implements Runnable {
        private Bank bank;
        private BankMapper bankMapper;
        private String type;
        private OkHttpClient client = new OkHttpClient();

        public BankCleanerTask(Bank bank, BankMapper bankMapper, String type) {
            this.bank = bank;
            this.bankMapper = bankMapper;
            this.type = type;
        }

        @Override
        public void run() {
            String correctionCode = bank.getCorrectionCode();
            try {
                // 呼叫外部API
                String apiResponse = callExternalApi(bank);
                if (correctionCode.matches("0|11|12|13")) {
                    updateDatabase(bank.getId(), "latestAddr", apiResponse);
                } else if (correctionCode.matches("[1-9]|10")) {
                    updateDatabase(bank.getId(), "normalized", apiResponse);
                    generateErrorReport(bank, apiResponse);
                }
                // 寫入LOG TABLE
                writeLog(bank, apiResponse);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        // 呼叫外部API
        private String callExternalApi(Bank bank) throws IOException {
            // 構建API請求
            Request request = new Request.Builder()
                .url("https://example.com/api?param=" + bank.getBranchAddr()) // 替換為實際API URL
                .build();

            // 執行API請求
            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful()) throw new IOException("Unexpected code " + response);

                // 返回API回應
                return response.body().string();
            }
        }

        // 更新資料庫中的地址
        private void updateDatabase(int id, String columnName, String newValue) {
            bankMapper.updateAddress(id, columnName, newValue);
        }

        // 生成錯誤報表
        private void generateErrorReport(Bank bank, String apiResponse) {
            String errorReport;
            if (type.equals("XY")) {
                errorReport = String.format("Agent Code: %s, 網點代碼: %s, 銀行名稱: %s, 地址: %s, 正規後地址: %s, 錯誤訊息: %s",
                        bank.getAgentCode(), bank.getBankCode(), bank.getBankName(), bank.getBranchAddr(), apiResponse, "錯誤原因");
            } else {
                errorReport = String.format("銀行簡稱: %s, 銀行: %s, 分行: %s, 分行地址: %s, 市話: %s, 是否比對成功: 否, 失敗原因: %s, 校正結果: %s",
                        bank.getBankAbbreviation(), bank.getBank(), bank.getBranch(), bank.getBranchAddr(), bank.getPhone(), "錯誤原因", apiResponse);
            }
            System.out.println(errorReport);
        }

        // 寫入LOG TABLE
        private void writeLog(Bank bank, String apiResponse) {
            LogEntry logEntry = new LogEntry(bank.getId(), apiResponse);
            bankMapper.insertLog(logEntry);
        }
    }

    // 清理業務員數據的公共任務
    static class AgentCleanerTask implements Runnable {
        private Agent agent;
        private AgentMapper agentMapper;
        private String type;
        private OkHttpClient client = new OkHttpClient();

        public AgentCleanerTask(Agent agent, AgentMapper agentMapper, String type) {
            this.agent = agent;
            this.agentMapper = agentMapper;
            this.type = type;
        }

        @Override
        public void run() {
            String correctionCode = agent.getCorrectionCode();
            try {
                // 呼叫兩次外部API
                String apiResponse1 = callExternalApi(agent, "addr1");
                String apiResponse2 = callExternalApi(agent, "addr2");

                // 更新資料庫中的地址
                updateDatabase(agent.getId(), apiResponse1, apiResponse2);

                // 生成錯誤報表
                generateErrorReport(agent, apiResponse1, apiResponse2);

                // 寫入LOG TABLE
                writeLog(agent, apiResponse1, apiResponse2);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        // 呼叫外部API
        private String callExternalApi(Agent agent, String addrField) throws IOException {
            // 構建API請求
            String addr = addrField.equals("addr1") ? agent.getAddr1() : agent.getAddr2();
            Request request = new Request.Builder()
                .url("https://example.com/api?param=" + addr) // 替換為實際API URL
                .build();

            // 執行API請求
            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful()) throw new IOException("Unexpected code " + response);

                // 返回API回應
                return response.body().string();
            }
        }

        // 更新資料庫中的地址
        private void updateDatabase(int id, String addr1Response, String addr2Response) {
            agentMapper.updateAddress(id, "latestAddr1", addr1Response);
            agentMapper.updateAddress(id, "latestAddr2", addr2Response);
        }

        // 生成錯誤報表
        private void generateErrorReport(Agent agent, String apiResponse1, String apiResponse2) {
            String errorReport;
            if (type.equals("XY")) {
                errorReport = String.format("身分證字號: %s, 姓名: %s, 登錄字號: %s, 地址1: %s, 地址2: %s, 通路代號: %s, 員工編號: %s, 註銷日: %s, 正規後地址1: %s, 正規後地址2: %s, 錯誤訊息: %s",
                        agent.getIdNumber(), agent.getName(), agent.getLoginNumber(), agent.getAddr1(), agent.getAddr2(), agent.getChannelCode(), agent.getEmployeeNumber(), agent.getCancellationDate(), apiResponse1, apiResponse2, "錯誤原因");
            } else {
                errorReport = String.format("員工編號: %s, 姓名: %s, 人員單位: %s, 原提供地址1: %s, 原提供地址2: %s, 錯誤資訊: %s, %s",
                        agent.getEmployeeNumber(), agent.getName(), agent.getDepartment(), agent.getAddr1(), agent.getAddr2(), apiResponse1, apiResponse2);
            }
            System.out.println(errorReport);
        }

        // 寫入LOG TABLE
        private void writeLog(Agent agent, String apiResponse1, String apiResponse2) {
            LogEntry logEntry = new LogEntry(agent.getId(), apiResponse1 + ", " + apiResponse2);
            agentMapper.insertLog(logEntry);
        }
    }
}

// MyBatis Mapper接口
public interface BankMapper {
    List<Bank> selectXYBanks(@Param("bankCodePattern") String bankCodePattern, @Param("offset") int offset, @Param("pageSize") int pageSize);

    List<Bank> selectCHBanks(@Param("bankCodePattern") String bankCodePattern, @Param("offset") int offset, @Param("pageSize") int pageSize);

    void updateAddress(int id, String columnName, String newValue);

    void insertLog(LogEntry logEntry);
}

public interface AgentMapper {
    List<Agent> selectXYAgents(@Param("agentCodePattern") String agentCodePattern, @Param("offset") int offset, @Param("pageSize") int pageSize);

    List<Agent> selectCHAgents(@Param("agentCodePattern") String agentCodePattern, @Param("offset") int offset, @Param("pageSize") int pageSize);

    void updateAddress(int id, String columnName, String newValue);

    void insertLog(LogEntry logEntry);
}

// Bank實體類
public class Bank {
    private int id;
    private String agentCode;
    private String bankCode;
    private String bankName;
    private String branchAddr;
    private String latestAddr;
    private String normalized;
    private String correctionCode;
    private String bankAbbreviation;
    private String bank;
    private String branch;
    private String phone;

    // getters and setters
}

// Agent實體類
public class Agent {
    private int id;
    private String agentCode;
    private String name;
    private String addr1;
    private String addr2;
    private String idNumber;
    private String loginNumber;
    private String channelCode;
    private String employeeNumber;
    private String cancellationDate;
    private String correctionCode;
    private String department;

    // getters and setters
}

// LogEntry實體類
public class LogEntry {
    private int entityId;
    private String apiResponse;

    public LogEntry(int entityId, String apiResponse) {
        this.entityId = entityId;
        this.apiResponse = apiResponse;
    }

    // getters and setters
}
