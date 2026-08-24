package com.dataforge.expansion;

import com.dataforge.model.ColumnDef;
import com.dataforge.model.DataRow;
import com.dataforge.model.TableMeta;
import com.dataforge.pipeline.PipelineContext;

import java.util.List;
import java.util.Map;

/**
 * 行构建器（展开器）接口 —— "LLM 定义模板、规则实例化"（PLAN §4.7 两阶段生成）。
 *
 * <p>语义源表（category/user/product）由 LLM 产样本，派生表由展开器
 * <b>零 LLM</b> 规则派生：锚点（父表样本列值）+ 蓝图（规格系数/分布权重）。</p>
 *
 * <p>血缘：派生值标注 {@link com.dataforge.model.SourceTag#EXPAND}。</p>
 */
public interface Expander {

    /** 本展开器负责的表名 */
    String tableName();

    /**
     * 用样本数据 + 目标行数展开派生表。
     *
     * @param table         目标表结构
     * @param context       管线上下文（确定性随机源）
     * @param completedData 已生成表的列值索引（table → column → [值...]，按行序）
     * @return 完整行（含血缘 EXPAND）
     */
    List<DataRow> expand(TableMeta table, PipelineContext context,
                         Map<String, Map<String, List<Object>>> completedData);

    /** 血缘标注：派生行全部标 EXPAND（规则实例化，非 LLM） */
    default DataRow tagAll(Map<String, Object> values, TableMeta table) {
        Map<String, com.dataforge.model.SourceTag> lineage = new java.util.LinkedHashMap<>();
        for (ColumnDef col : table.columns()) {
            lineage.put(col.name(), com.dataforge.model.SourceTag.EXPAND);
        }
        return new DataRow(values, lineage);
    }
}
