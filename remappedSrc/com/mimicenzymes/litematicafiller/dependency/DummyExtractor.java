package com.mimicenzymes.litematicafiller.dependency;

public class DummyExtractor implements IShulkerExtractor {
    @Override
    public boolean requestOpenShulker(int playerSlotIndex) {
        // 没有安装前置，直接拒绝请求
        return false; 
    }
}