import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 实体类
 * 该类配合@RequestBody注解使用，用于解析前端发送的请求参数
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EvaluationRequest {

    // 实体名（物理机/虚拟机/容器...）
    private String entity;
    // 训练数据的开始时间戳
    private Long startTimestamp;
    // 训练数据的结束时间戳
    private Long endTimestamp;
    // 调用的评估算法名
    private String algorithm;

}
