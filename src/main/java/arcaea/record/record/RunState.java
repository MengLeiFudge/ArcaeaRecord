package arcaea.record.record;

/**
 * 脚本运行类型.
 */
public enum RunState {
    // 【推荐】歌曲开始时运行。
    // 指点击曲目，游戏“关门”后，就开始运行脚本。
    SONG_START_BEGIN,
    // 【不推荐】首键到达判定线时运行。
    // 指开始曲目后按住脚本开始按钮，第一个键到达判定位置时松手。
    FIRST_NOTE_BEGIN,
    // 两种运行脚本都生成。
    BOTH
}
