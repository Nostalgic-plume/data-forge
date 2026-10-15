package com.dataforge.expansion;

import com.dataforge.model.ColumnDef;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 固定样本表（零 LLM、零展开）：语义硬编码的表（shop 库代码引用这些 id/值）。
 *
 * <ul>
 *   <li>role：代码硬编码 id 100=管理员 101=商家 102=普通用户（DDL 注释明示）</li>
 *   <li>admin：2 个管理员账号（验收库账号 admin）</li>
 * </ul>
 *
 * <p>固定表的 PK 骨架在 PkGen 后覆盖（fixed id），FkAlloc 引用正常。</p>
 */
public final class FixedSamples {

    private FixedSamples() {}

    /**
     * 是否为固定样本表（role / admin）。
     *
     * <p><b>与 generation-plan.json 的一致性约束</b>：{@code PkGen} 对这两张表只登记<b>固定 id
     * 池</b>（role 为 100/101/102 三条）。若 plan 把它们判成 {@code source} 而走 LLM 分支、
     * 且请求行数超过固定条数，多出来的行拿不到 id → 整表失败（实测：请求 role 5 行时复现）。
     * 故它们在 plan 里必须标 {@code fixed}——{@code PlanDrivenExpansionTest} 的全表用例会锁住这点。</p>
     */
    public static boolean isFixed(String tableName) {
        return "role".equals(tableName) || "admin".equals(tableName);
    }

    /**
     * 生成固定表行（值按列名匹配，缺列补 null——NOT NULL 列必须全覆盖）。
     *
     * <p>category 委托给 {@link CategoryTree}：与 role/admin 不同，它的值不在 Java 里硬编码，
     * 而由本地文件 {@code category-tree.json} 驱动（分类名是业务数据、会变，不该编进代码）。</p>
     *
     * <p><b>为什么 category 不出现在 {@link #isFixed}</b>：本方法只回答"给我这表的行"，
     * 而"这表算不算固定表"的裁决分两层——有计划时看 {@code plan.builder == fixed}
     * （{@code ContentGen} / {@code PkGen} 都查这条），无计划时才回落本类的名字硬编码。
     * 把 category 写进 {@code isFixed} 会让"无计划 fallback"这条通用路径也强制走固定表，
     * 破坏该路径上"语义列走 LLM"的既有行为（实测直接打挂 ExpansionSmokeTest / PipelineSmokeTest）。</p>
     */
    public static List<Map<String, Object>> rows(String tableName, List<ColumnDef> columns, Random rng) {
        if ("role".equals(tableName)) return roleRows(columns, rng);
        if ("admin".equals(tableName)) return adminRows(columns, rng);
        if (CategoryTree.isCategory(tableName)) return CategoryTree.rows(columns, rng);
        return List.of();
    }

    private static List<Map<String, Object>> roleRows(List<ColumnDef> columns, Random rng) {
        String[] roles = {
                "100|管理员|admin|系统管理员",
                "101|商家|merchant|店铺商家",
                "102|普通用户|user|普通注册用户",
        };
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String role : roles) {
            String[] parts = role.split("\\|");
            Map<String, Object> row = new LinkedHashMap<>();
            String createTime = ValuePools.randomDateTime(365, rng);
            for (ColumnDef col : columns) {
                row.put(col.name(), switch (col.name()) {
                    case "id" -> Long.parseLong(parts[0]);
                    case "name" -> parts[1];
                    case "code" -> parts[2];
                    case "description" -> parts[3];
                    case "status" -> 0;
                    case "create_time" -> createTime;
                    case "update_time" -> ValuePools.laterDateTime(createTime, rng);
                    case "deleted" -> 0;
                    default -> null;
                });
            }
            rows.add(row);
        }
        return rows;
    }

    private static List<Map<String, Object>> adminRows(List<ColumnDef> columns, Random rng) {
        String[] admins = {
                "1|admin|超级管理员|2",
                "2|manager|运营管理员|1",
        };
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String admin : admins) {
            String[] parts = admin.split("\\|");
            Map<String, Object> row = new LinkedHashMap<>();
            String createTime = ValuePools.randomDateTime(365, rng);
            for (ColumnDef col : columns) {
                row.put(col.name(), switch (col.name()) {
                    case "id" -> Long.parseLong(parts[0]);
                    case "username" -> parts[1];
                    case "password" -> "$2a$10$" + "DataForgePlaceholderHash"; // BCrypt 占位（导入后统一替换）
                    case "nickname" -> parts[2];
                    case "admin_level" -> Integer.parseInt(parts[3]);
                    case "status" -> 0;
                    case "create_time" -> createTime;
                    case "update_time" -> ValuePools.laterDateTime(createTime, rng);
                    case "deleted" -> 0;
                    default -> null;
                });
            }
            rows.add(row);
        }
        return rows;
    }
}
