package com.dataforge.correction;

import com.dataforge.agent.CorrectionAgent;
import com.dataforge.config.GeneratorProperties;
import org.springframework.stereotype.Component;

/**
 * 修正 agent 默认实现：编排 {@link CorrectionEngine} 的 ReAct 收敛循环。
 * 收敛上限取 {@link GeneratorProperties#maxIterations()}。
 */
@Component
class DefaultCorrectionAgent implements CorrectionAgent {

    private final CorrectionEngine engine;
    private final GeneratorProperties props;

    DefaultCorrectionAgent(CorrectionEngine engine, GeneratorProperties props) {
        this.engine = engine;
        this.props = props;
    }

    @Override
    public CorrectionResult correct(CorrectionRequest req) {
        return engine.run(req, props.maxIterations());
    }
}
