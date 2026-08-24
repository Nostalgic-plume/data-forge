package com.dataforge.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 历史记录服务（P7 Web）：每次分析/生成/修正/评分写入 history/*.json
 * （含动作、耗时、模式、行数等摘要——面试叙事：token/耗时/成本记录）。
 */
@Service
public class HistoryService {

    private static final Logger log = LoggerFactory.getLogger(HistoryService.class);
    private static final Path HISTORY_DIR = Path.of("history");

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 记录一次操作摘要（落盘 history/{ts}.json） */
    public void record(String action, Map<String, Object> summary) {
        try {
            Files.createDirectories(HISTORY_DIR);
            Map<String, Object> entry = new java.util.LinkedHashMap<>();
            entry.put("action", action);
            entry.put("timestamp", Instant.now().toString());
            entry.putAll(summary);
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(
                    HISTORY_DIR.resolve(System.currentTimeMillis() + ".json").toFile(), entry);
        } catch (Exception e) {
            log.warn("历史记录写入失败: {}", e.getMessage());
        }
    }

    /** 列出历史（新→旧） */
    public List<Map<String, Object>> list() {
        List<Map<String, Object>> history = new ArrayList<>();
        if (!Files.isDirectory(HISTORY_DIR)) return history;
        try (var stream = Files.list(HISTORY_DIR)) {
            stream.filter(p -> p.toString().endsWith(".json")).sorted((a, b) -> b.toString().compareTo(a.toString()))
                    .forEach(p -> {
                        try {
                            history.add(objectMapper.readValue(p.toFile(),
                                    objectMapper.getTypeFactory().constructMapType(Map.class, String.class, Object.class)));
                        } catch (Exception ignored) {
                            // 单条损坏忽略
                        }
                    });
        } catch (Exception e) {
            log.warn("历史读取失败: {}", e.getMessage());
        }
        return history;
    }
}
