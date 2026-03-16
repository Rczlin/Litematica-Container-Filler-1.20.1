package com.mimicenzymes.litematicafiller.render;

public enum HighlightState {
    UNFILLED,    // 完全未填充
    PARTIAL,     // 填充没完成或格子不对
    OVERFILLED,  // 填多了 / 有多余杂物
    WRONG_ITEM,  // 填错了物品
    UNKNOWN,     // 数据未知 / 正在同步
    SATISFIED    // 完美满足
}