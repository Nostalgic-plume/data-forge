package com.dataforge.expansion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 规格模板加载（{@code byAncestor} 列模式用）：祖先行的名称 → 各维度的候选值池。
 *
 * <p>配置文件（默认 {@code category-specs.json}）与 {@code generation-plan.json} 同级，
 * 属本地文件、不入库。按路径缓存，改文件后需重启服务生效。</p>
 *
 * <p>缺失或非法配置 → 返回空表并 warn（不抛）：规格列允许为空，此时该列落 null；
 * 但必须留下日志，否则"文件路径写错"会表现为"规格莫名全空"而无人察觉。</p>
 */
final class SpecTemplates {

    private static final Logger log = LoggerFactory.getLogger(SpecTemplates.class);
    private static final Map<String, Map<String, Map<String, List<String>>>> CACHE = new ConcurrentHashMap<>();

    private SpecTemplates() {}

    static Map<String, Map<String, List<String>>> load(String path) {
        String p = (path == null || path.isBlank()) ? "category-specs.json" : path;
        return CACHE.computeIfAbsent(p, SpecTemplates::read);
    }

    /** 解析：{@code {"手机": {"memory": "8GB,12GB", ...}, ...}}；以下划线开头的说明键跳过 */
    private static Map<String, Map<String, List<String>>> read(String path) {
        try {
            JsonNode root = new ObjectMapper().readTree(new File(path));
            Map<String, Map<String, List<String>>> out = new LinkedHashMap<>();
            root.fields().forEachRemaining(top -> {
                if (top.getKey().startsWith("_")) return;
                Map<String, List<String>> dims = new LinkedHashMap<>();
                top.getValue().fields().forEachRemaining(dim -> {
                    if (!dim.getValue().isTextual()) return;
                    List<String> pool = new ArrayList<>();
                    for (String v : dim.getValue().asText().split(",")) {
                        if (!v.isBlank()) pool.add(v.trim());
                    }
                    if (!pool.isEmpty()) dims.put(dim.getKey(), pool);
                });
                if (!dims.isEmpty()) out.put(top.getKey(), dims);
            });
            log.info("规格模板已加载：{} 条（{}）", out.size(), path);
            return out;
        } catch (Exception e) {
            log.warn("规格模板加载失败（规格列将落空）：{} — {}", path, e.getMessage());
            return Map.of();
        }
    }
}
