package com.dataforge.service;

import com.dataforge.db.MysqlJdbcDataSource;
import com.dataforge.model.ColumnDef;
import com.dataforge.model.DataRow;
import com.dataforge.model.GeneratedData;
import com.dataforge.model.SourceTag;
import com.dataforge.model.TableMeta;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 真实库表读取（R1，软评分参考分布，CLI/Web 重复逻辑统一）。
 * 血缘全标 SEED；标识符统一 quote() 防注入（Web 旧实现裸反引号，统一用 CLI 的 quote）。
 */
public final class RealTableReader {

    private RealTableReader() {}

    public static Map<String, GeneratedData> read(MysqlJdbcDataSource jdbc, String schema, List<TableMeta> tables) {
        Map<String, GeneratedData> real = new LinkedHashMap<>();
        for (TableMeta t : tables) {
            String sql = "SELECT * FROM " + MysqlJdbcDataSource.quote(schema) + "."
                    + MysqlJdbcDataSource.quote(t.name());
            List<Map<String, Object>> rows = jdbc.query(sql);
            List<DataRow> dataRows = rows.stream().map(r -> {
                Map<String, SourceTag> lineage = new LinkedHashMap<>();
                for (ColumnDef c : t.columns()) lineage.put(c.name(), SourceTag.SEED);
                return new DataRow(new LinkedHashMap<>(r), lineage);
            }).toList();
            real.put(t.name(), new GeneratedData(t.name(), t.columns(), dataRows));
        }
        return real;
    }
}
