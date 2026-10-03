package com.evops.config;

import org.apache.shiro.realm.Realm;
import org.apache.shiro.spring.web.config.DefaultShiroFilterChainDefinition;
import org.apache.shiro.spring.web.config.ShiroFilterChainDefinition;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ShiroConfig {

    @Bean
    public Realm localRealm() {
        return new ColdChainRealm();
    }

    /**
     * ADMIN 角色过滤器：仅校验不重定向（未认证 401、已认证非管理员 403）。
     * 温控预警模板维护、预警查看/导出、手工扫描统一挂在该过滤器之后。
     */
    @Bean
    public AdminRoleFilter adminRoleFilter() {
        return new AdminRoleFilter();
    }

    /** 阻止 Spring Boot 把该 Shiro 内部过滤器再向 Servlet 容器注册一遍（避免双重执行） */
    @Bean
    public FilterRegistrationBean<AdminRoleFilter> adminRoleFilterServletRegistration(AdminRoleFilter filter) {
        FilterRegistrationBean<AdminRoleFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    public ShiroFilterChainDefinition shiroFilterChainDefinition() {
        DefaultShiroFilterChainDefinition chain = new DefaultShiroFilterChainDefinition();
        chain.addPathDefinition("/api/health", "anon");
        chain.addPathDefinition("/error", "anon");
        // V5 管理员专属：类别/模板维护、温度预警查看与 CSV 导出、手工触发扫描
        chain.addPathDefinition("/api/cold-chain/admin/**", "authcBasic, adminRoleFilter");
        chain.addPathDefinition("/ui/cold-chain/warning-admin", "authcBasic, adminRoleFilter");
        // 冷链台账接口与管理页面使用 HTTP Basic，便于 curl/联调；浏览器会弹出登录框
        chain.addPathDefinition("/api/**", "authcBasic");
        chain.addPathDefinition("/ui/**", "authcBasic");
        chain.addPathDefinition("/**", "authc");
        return chain;
    }
}
