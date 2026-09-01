package com.dataforge.service;

import com.dataforge.model.ProjectRules;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 规则加载（R1，CLI/Web 重复逻辑统一）：优先已落盘 project_rules.json，否则内置默认修正规则。
 * 加载成功 info 日志、失败 warn + 回退，不中断流程。
 */
@Service
public class RulesService {

    private static final Logger log = LoggerFactory.getLogger(RulesService.class);
    private static final Path RULES_FILE = Path.of("project_rules.json");

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 当前生效规则：优先已保存的 project_rules.json，否则内置默认修正规则 */
    public ProjectRules currentRules() {
        try {
            if (Files.exists(RULES_FILE)) {
                ProjectRules loaded = objectMapper.readValue(RULES_FILE.toFile(), ProjectRules.class);
                log.info("加载分析规则 project_rules.json（correction {} 条）", loaded.correctionRules().size());
                return loaded;
            }
        } catch (Exception e) {
            log.warn("project_rules.json 读取失败（回退内置默认）: {}", e.getMessage());
        }
        return ProjectRules.withDefaultCorrectionRules();
    }
}
