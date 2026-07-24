package com.admin;

import com.admin.controller.BaseController;
import com.admin.entity.BaseEntity;
import com.baomidou.mybatisplus.core.exceptions.MybatisPlusException;
import com.baomidou.mybatisplus.generator.FastAutoGenerator;
import com.baomidou.mybatisplus.generator.config.OutputFile;
import com.baomidou.mybatisplus.generator.config.rules.NamingStrategy;
import com.baomidou.mybatisplus.generator.engine.FreemarkerTemplateEngine;

import java.nio.file.Paths;
import java.util.Collections;
import java.util.Scanner;

public final class CodeGenerator {

    private CodeGenerator() {
    }

    public static void main(String[] args) {
        String dbHost = requireEnvironment("DB_HOST");
        String dbName = requireEnvironment("DB_NAME");
        String dbUser = requireEnvironment("DB_USER");
        String dbPassword = requireEnvironment("DB_PASSWORD");
        String projectPath = System.getProperty("user.dir");
        String tables = readTables();
        String url = "jdbc:mysql://" + dbHost + ":3306/" + dbName
                + "?useUnicode=true&useSSL=false&characterEncoding=utf8"
                + "&serverTimezone=Asia/Shanghai&remarks=true&useInformationSchema=true";

        FastAutoGenerator.create(url, dbUser, dbPassword)
                .globalConfig(builder -> builder
                        .author("QAQ")
                        .disableOpenDir()
                        .outputDir(Paths.get(projectPath, "src", "main", "java").toString()))
                .packageConfig(builder -> builder
                        .parent("com.admin")
                        .pathInfo(Collections.singletonMap(
                                OutputFile.xml,
                                Paths.get(projectPath, "src", "main", "resources", "mapper").toString())))
                .strategyConfig(builder -> builder
                        .addInclude(tables.split(","))
                        .entityBuilder()
                        .naming(NamingStrategy.underline_to_camel)
                        .columnNaming(NamingStrategy.underline_to_camel)
                        .superClass(BaseEntity.class)
                        .addSuperEntityColumns("id", "created_time", "updated_time", "status")
                        .enableLombok()
                        .controllerBuilder()
                        .superClass(BaseController.class)
                        .enableRestStyle()
                        .enableHyphenStyle()
                        .serviceBuilder()
                        .formatServiceFileName("%sService")
                        .build())
                .templateEngine(new FreemarkerTemplateEngine())
                .execute();
    }
        
    private static String requireEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new MybatisPlusException("Missing database environment variable: " + name);
        }
        return value;
    }

    private static String readTables() {
        System.out.print("请输入表名，多个英文逗号分隔：");
        Scanner scanner = new Scanner(System.in);
        if (scanner.hasNext()) {
            String value = scanner.next().trim();
            if (!value.isEmpty()) {
                return value;
            }
        }
        throw new MybatisPlusException("请输入正确的表名！");
    }
}
