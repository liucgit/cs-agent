package com.demo.cs.config;

import com.demo.cs.ticket.Ticket;
import com.demo.cs.ticket.TicketRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 启动时预置测试工单数据（简历要求：初始化一定规模的测试业务数据）。
 *
 * <p>数据设计原则：覆盖四种状态 × 四种优先级 × 多个员工，
 * 让"查工单/搜工单/建工单"三个工具都有话可说。
 * 只有当库里没有数据时才初始化（幂等：重复启动不会重复插入）。</p>
 */
@Component
public class TicketSeedRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(TicketSeedRunner.class);

    private final TicketRepository repository;

    public TicketSeedRunner(TicketRepository repository) {
        this.repository = repository;
    }

    @Override
    public void run(String... args) {
        if (repository.count() > 0) {
            log.info("工单数据已存在（{} 条），跳过初始化", repository.count());
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        List<Ticket> seeds = List.of(
                // ---- E1001 张伟：IT 类 ----
                new Ticket("TCK-1001", "E1001", "打印机无法打印", "3楼东侧会议室打印机无法打印，显示缺纸但纸盒已满，影响下午评审会使用", "PROCESSING", "HIGH", now.minusDays(3), now.minusDays(1)),
                new Ticket("TCK-1002", "E1001", "VPN 连接超时", "居家办公连 VPN 一直超时，主入口和备用入口都试过，昨晚开始出现", "OPEN", "MEDIUM", now.minusDays(1), now.minusDays(1)),
                new Ticket("TCK-1003", "E1001", "显示器闪烁", "外接显示器画面间歇性闪烁，怀疑线材问题", "RESOLVED", "LOW", now.minusDays(10), now.minusDays(8)),
                new Ticket("TCK-1004", "E1001", "报销单被驳回", "差旅报销单被财务驳回，提示发票验真失败，需要协助排查", "OPEN", "MEDIUM", now.minusDays(2), now.minusDays(2)),

                // ---- E1002 李娜：办公环境类 ----
                new Ticket("TCK-1005", "E1002", "空调不制冷", "工位区域空调不制冷，下午室温超过 30 度，多人受影响", "PROCESSING", "URGENT", now.minusDays(1), now.minusHours(20)),
                new Ticket("TCK-1006", "E1002", "申请第二块显示器", "数据分析需要双屏环境，申请加配一块 27 寸显示器", "OPEN", "LOW", now.minusDays(5), now.minusDays(5)),
                new Ticket("TCK-1007", "E1002", "工位插座损坏", "工位两个插座只有一个能用，充电排队", "RESOLVED", "MEDIUM", now.minusDays(15), now.minusDays(13)),
                new Ticket("TCK-1008", "E1002", "门禁卡消磁", "门禁卡刷不开 3 楼实验室，怀疑消磁", "CLOSED", "LOW", now.minusDays(30), now.minusDays(28)),

                // ---- E1003 王强：系统权限类 ----
                new Ticket("TCK-1009", "E1003", "申请生产库只读权限", "排查线上数据问题需要生产库只读权限，期限一周", "PROCESSING", "HIGH", now.minusHours(30), now.minusHours(24)),
                new Ticket("TCK-1010", "E1003", "GitLab 仓库权限", "申请加入 order-service 仓库 Developer 角色", "OPEN", "MEDIUM", now.minusDays(2), now.minusDays(2)),
                new Ticket("TCK-1011", "E1003", "堡垒机会话中断", "堡垒机会话每 10 分钟自动断开，疑似超时配置过短", "OPEN", "HIGH", now.minusDays(4), now.minusDays(4)),
                new Ticket("TCK-1012", "E1003", "邮箱容量告警", "邮箱使用率 95%，申请扩容或指导归档", "RESOLVED", "LOW", now.minusDays(20), now.minusDays(18)),

                // ---- E1004 赵敏：账号类 ----
                new Ticket("TCK-1013", "E1004", "账号被锁定", "登录域账号连续输错密码被锁，急需要登系统处理线上问题", "RESOLVED", "URGENT", now.minusDays(6), now.minusDays(6)),
                new Ticket("TCK-1014", "E1004", "两步验证手机丢失", "换手机后两步验证器数据丢失，无法登录内部系统", "PROCESSING", "HIGH", now.minusDays(3), now.minusHours(40)),
                new Ticket("TCK-1015", "E1004", "新员工账号开通", "新同事下周一入职，提前申请域账号与企业微信", "CLOSED", "MEDIUM", now.minusDays(12), now.minusDays(9)),

                // ---- E1005 刘洋：软件与网络 ----
                new Ticket("TCK-1016", "E1005", "IDEA License 过期", "IDEA 提示 License 即将过期，请协助续期", "OPEN", "LOW", now.minusDays(7), now.minusDays(7)),
                new Ticket("TCK-1017", "E1005", "会议室投屏失败", "5 楼大会议室投屏器搜索不到信号，视频会议紧急", "RESOLVED", "HIGH", now.minusDays(9), now.minusDays(9)),
                new Ticket("TCK-1018", "E1005", "网速极慢", "下午 3 点起网速明显变慢，ping 延迟 300ms+", "CLOSED", "MEDIUM", now.minusDays(25), now.minusDays(24)),

                // ---- E1006 陈晨：更多样化 ----
                new Ticket("TCK-1019", "E1006", "笔记本键盘进水", "咖啡洒到键盘上，部分按键失灵，申请备用机", "PROCESSING", "URGENT", now.minusHours(5), now.minusHours(3)),
                new Ticket("TCK-1020", "E1006", "申请加密U盘", "需要向客户交付涉密资料，借用加密 U 盘", "OPEN", "MEDIUM", now.minusDays(1), now.minusDays(1)),
                new Ticket("TCK-1021", "E1006", "考勤异常申诉", "系统显示 10 月 20 日缺卡，实际已打卡，申请补卡", "RESOLVED", "LOW", now.minusDays(18), now.minusDays(16)),
                new Ticket("TCK-1022", "E1006", "工牌照片更换", "工牌照片还是学生时代的，申请更换", "OPEN", "LOW", now.minusDays(11), now.minusDays(11)),
                new Ticket("TCK-1023", "E1006", "共享盘权限", "加入市场部共享盘后看不到 2026 年文件夹", "OPEN", "MEDIUM", now.minusDays(8), now.minusDays(8)),
                new Ticket("TCK-1024", "E1006", "视频会议回声", "与客户开会时对方听到回声，疑似会议室麦克风问题", "PROCESSING", "MEDIUM", now.minusDays(2), now.minusDays(1))
        );
        repository.saveAll(seeds);
        log.info("预置测试工单 {} 条（覆盖 6 名员工 × 4 状态 × 4 优先级）", seeds.size());
    }
}
