package com.dataforge.validation;

import com.dataforge.model.ProjectRules;
import com.dataforge.model.TableMeta;

/**
 * 规则编译器（API_CONTRACT §3）：规则 → 检查 SQL。
 *
 * <p><b>信任模型红线</b>：规则提炼用 LLM（一次性 + 人工确认），规则执行用确定性代码。
 * RuleCompiler 是"生成 SQL 的代码"，必须被单测覆盖。SQL 为受控模板（非 LLM 自由拼接）。</p>
 */
public interface RuleCompiler {

    /** 通用检查类型（从 DDL 元数据自动推导） */
    enum MetaCheckType { FK_ORPHAN, UNIQUE_CONFLICT }

    /** 通用规则 → 检查 SQL（staging 库内扫描） */
    String compileMetaCheck(TableMeta table, MetaCheckType type, String stagingSchema);

    /** 项目规则（后置修正节）→ 检查 SQL（金额对账等） */
    String compileProjectRule(ProjectRules.Rule rule, TableMeta table, String stagingSchema);
}
