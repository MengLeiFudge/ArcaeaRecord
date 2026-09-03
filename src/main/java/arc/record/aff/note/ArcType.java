package arc.record.aff.note;

/**
 * AFF 中 Arc 的原始输入类型。
 *
 * <p>该类型参与首尾连接判定，不能由是否产生持续判定或是否包含 Arctap 反推。</p>
 */
public enum ArcType {
    FALSE("false"),
    TRUE("true"),
    DESIGNANT("designant");

    private final String affValue;

    ArcType(String affValue) {
        this.affValue = affValue;
    }

    /**
     * 解析 AFF 中的 Arc 类型字段。
     *
     * @param value AFF 原始字段
     * @return 对应 Arc 类型
     */
    public static ArcType fromAffValue(String value) {
        for (ArcType type : values()) {
            if (type.affValue.equals(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("未知 Arc 类型：" + value);
    }

    /**
     * 指示该类型是否能够产生持续 Arc 判定。
     *
     * @return 仅原始 false 类型返回 true
     */
    public boolean acceptsInput() {
        return this == FALSE;
    }
}
