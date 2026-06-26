package com.ba.analyzer.support;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

public final class RepoTestBase {
    private RepoTestBase() {}

    public static JdbcTemplate testJdbc() {
        String url = System.getenv().getOrDefault("DB_TEST_URL",
                "jdbc:mysql://127.0.0.1:3306/ba_test?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai");
        String user = System.getenv().getOrDefault("DB_TEST_USER", "root");
        String pass = System.getenv().getOrDefault("DB_TEST_PASSWORD", "");
        DriverManagerDataSource ds = new DriverManagerDataSource(url, user, pass);
        ds.setDriverClassName("com.mysql.cj.jdbc.Driver");
        return new JdbcTemplate(ds);
    }
}
