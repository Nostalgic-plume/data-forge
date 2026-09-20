package com.dataforge.db;

import com.dataforge.expansion.PlanBuilder;
import com.dataforge.model.GenerationPlan;
import com.dataforge.model.TableMeta;
import com.dataforge.parser.DdlParser;
import com.dataforge.model.PipelineContext;
import com.dataforge.pipeline.PkGen;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 批次1 单测（决策 A，修 D2）：源码语义扫描 + PlanBuilder 消费。
 *
 * <p>覆盖：@TableName 表名 / @Schema 列注释 / @TableId PK 策略 / 枚举常量 /
 * 无注解类名兜底 / PlanBuilder 用源码枚举产出 enum 列模式。</p>
 */
class SourceScannerTest {

    @TempDir
    Path tempDir;

    @Test
    void scanExtractsTableColumnsEnumAndPk() throws Exception {
        Path src = tempDir.resolve("entity");
        Files.createDirectories(src);
        Files.writeString(src.resolve("UserDO.java"),
                "package demo;\n"
                        + "import com.baomidou.mybatisplus.annotation.*;\n"
                        + "import io.swagger.v3.oas.annotations.media.Schema;\n"
                        + "@TableName(\"user\")\n"
                        + "public class UserDO {\n"
                        + "  @TableId(type = IdType.ASSIGN_ID)\n"
                        + "  @Schema(description = \"用户ID\")\n"
                        + "  private Long id;\n"
                        + "  @Schema(description = \"状态：0正常 1停用\")\n"
                        + "  private Integer status;\n"
                        + "}\n");
        Files.writeString(src.resolve("GenderEnum.java"),
                "public enum GenderEnum { MALE, FEMALE }\n");
        Files.writeString(src.resolve("ProfileDO.java"),
                "public class ProfileDO {\n"
                        + "  @Schema(description = \"性别\")\n"
                        + "  private GenderEnum gender;\n"
                        + "}\n");

        SourceScanner.SourceSemantics s = SourceScanner.scan(tempDir.toString());
        assertFalse(s.isEmpty(), "应扫描到源码语义");
        assertNotNull(s.tableColumns("user"), "user 表应有列语义");
        assertNotNull(s.tableColumns("user").get("status"), "user.status 应有语义");
        assertTrue(s.tableColumns("user").get("status").comment().contains("0正常"),
                "注释应含枚举描述");
        assertEquals("SNOWFLAKE", s.tableColumns("user").get("id").pkStrategy(),
                "ASSIGN_ID → SNOWFLAKE PK 提示");
        assertNotNull(s.tableColumns("profile"), "无 @TableName 类名应兜底转表名");
        assertEquals(List.of("MALE", "FEMALE"),
                s.tableColumns("profile").get("gender").enumValues());
    }

    @Test
    void camelToSnake() {
        assertEquals("create_time", SourceScanner.camelToSnake("createTime"));
        assertEquals("username", SourceScanner.camelToSnake("username"));
        assertEquals("user_role", SourceScanner.camelToSnake("UserRole"));
    }

    /** T1.3：PlanBuilder 消费源码枚举 → plan 列模式 enum 带显式值 */
    @Test
    void planBuilderUsesSourceEnums() throws Exception {
        String ddl = "CREATE TABLE profile (\n"
                + "  id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT 'ID',\n"
                + "  gender VARCHAR(20) COMMENT '性别'\n"
                + ");";
        List<TableMeta> tables = new DdlParser().parse(ddl).tables();
        Path src = tempDir.resolve("entity");
        Files.createDirectories(src);
        Files.writeString(src.resolve("GenderEnum.java"),
                "public enum GenderEnum { MALE, FEMALE, UNKNOWN }\n");
        Files.writeString(src.resolve("ProfileDO.java"),
                "public class ProfileDO {\n  private GenderEnum gender;\n}\n");

        SourceScanner.SourceSemantics sem = SourceScanner.scan(tempDir.toString());
        GenerationPlan plan = PlanBuilder.build(tables, sem);
        GenerationPlan.TablePlan tp = plan.tablePlan("profile");
        assertNotNull(tp, "profile 应有生成计划");
        GenerationPlan.ColumnPlan gender = tp.columns().stream()
                .filter(c -> c.column().equals("gender")).findFirst().orElse(null);
        assertNotNull(gender, "gender 列应有 plan");
        assertEquals("enum", gender.mode(), "源码枚举列 → enum 模式");
        assertTrue(gender.param("values") != null && gender.param("values").contains("MALE"),
                "enum 模式应带源码枚举值: " + gender.params());
    }

    /** A1：源码 @TableId 判定 → plan pkStrategy → PkGen 消费（逐表雪花/自增，不再全局一刀切） */
    @Test
    void pkStrategyHintFromSourceFeedsPkGen() throws Exception {
        Path src = tempDir.resolve("entity");
        Files.createDirectories(src);
        Files.writeString(src.resolve("UserDO.java"),
                "package demo;\n"
                        + "import com.baomidou.mybatisplus.annotation.*;\n"
                        + "@TableName(\"user\")\n"
                        + "public class UserDO {\n"
                        + "  @TableId(type = IdType.ASSIGN_ID)\n"
                        + "  private Long id;\n"
                        + "  private String username;\n"
                        + "}\n");
        String ddl = "CREATE TABLE user (\n"
                + "  id BIGINT PRIMARY KEY COMMENT '用户ID',\n"
                + "  username VARCHAR(50) COMMENT '用户名'\n"
                + ");";
        List<TableMeta> tables = new DdlParser().parse(ddl).tables();
        SourceScanner.SourceSemantics sem = SourceScanner.scan(tempDir.toString());
        GenerationPlan plan = PlanBuilder.build(tables, sem);
        assertEquals("SNOWFLAKE", plan.tablePlan("user").pkStrategy(),
                "ASSIGN_ID → plan pkStrategy=SNOWFLAKE");

        // PkGen 消费：hint 覆盖全局 sequential → 雪花 id（非 1..N 连续）
        PipelineContext ctx = new PipelineContext(tables, Map.of("user", 5), 42L);
        ctx.setPkStrategy("user", plan.tablePlan("user").pkStrategy());
        PkGen.execute(ctx, PkGen.Strategy.SEQUENTIAL);
        List<Object> ids = ctx.pkSkeletons().get("user").get("id");
        assertNotNull(ids);
        assertEquals(5, ids.size());
        assertFalse(ids.contains(1L), "雪花 id 不应是 1..N 连续");
    }
}
