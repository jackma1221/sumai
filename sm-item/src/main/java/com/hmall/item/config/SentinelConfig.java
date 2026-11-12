package com.hmall.item.config;

import com.alibaba.csp.sentinel.annotation.aspectj.SentinelResourceAspect;
import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRule;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRuleManager;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;

/**
 * 商品服务 Sentinel 配置：注解切面 + 流控规则 + 熔断降级规则。
 * <ul>
 *   <li>itemDetail：QPS 流控，超过阈值直接 429；</li>
 *   <li>itemDetailSlow：RT 熔断降级，慢调用比例超过 50%（RT>200ms）触发熔断，熔断 10 秒内走兜底。</li>
 * </ul>
 */
@Slf4j
@Configuration
public class SentinelConfig {

    @Value("${hm.sentinel.dashboard:127.0.0.1:8858}")
    private String dashboard;

    @Value("${hm.sentinel.item-qps:30}")
    private double itemDetailQps;

    @Bean
    public SentinelResourceAspect sentinelResourceAspect() {
        return new SentinelResourceAspect();
    }

    @PostConstruct
    public void initRules() {
        System.setProperty("csp.sentinel.dashboard.server", dashboard);
        System.setProperty("project.name", "item-service");
        System.setProperty("csp.sentinel.api.port", "8721");
        System.setProperty("csp.sentinel.log.dir", "logs/sentinel");

        // 1. 流控规则
        List<FlowRule> flowRules = new ArrayList<>();
        FlowRule detail = new FlowRule();
        detail.setResource("itemDetail");
        detail.setGrade(RuleConstant.FLOW_GRADE_QPS);
        detail.setCount(itemDetailQps);
        detail.setLimitApp("default");
        flowRules.add(detail);
        FlowRuleManager.loadRules(flowRules);

        // 2. 熔断降级规则（慢调用比例）
        List<DegradeRule> degradeRules = new ArrayList<>();
        DegradeRule slow = new DegradeRule();
        slow.setResource("itemDetailSlow");
        slow.setGrade(RuleConstant.DEGRADE_GRADE_RT);
        slow.setCount(200);              // 慢调用阈值：RT > 200ms
        slow.setTimeWindow(10);          // 熔断时长 10 秒
        slow.setMinRequestAmount(5);     // 统计窗口内至少 5 个请求才判断
        slow.setStatIntervalMs(1000);    // 统计窗口 1 秒
        slow.setSlowRatioThreshold(0.5); // 慢调用比例超过 50% 触发
        degradeRules.add(slow);
        DegradeRuleManager.loadRules(degradeRules);

        log.info("Sentinel 规则加载完成：itemDetail={} QPS；itemDetailSlow 熔断 RT>200ms/50%/10s，dashboard={}",
                itemDetailQps, dashboard);
    }
}
