package com.javallamaserver;

public class LlamaProxyServer {

    // DLL 所在的绝对路径（请确认这个路径是对的）
    private static final String NATIVE_PATH = "D:\\AIPROJECT\\Java-llama-server\\native\\";

    public static void main(String[] args) {
        System.out.println(">>> 开始暴力装载底层依赖...");

        // 按照“底层 -> 上层”的顺序，把依赖拉进内存
        // Windows 系统对重复加载同一个 DLL 不会报错，所以放心跑
        loadNative("ggml-base.dll");
        loadNative("ggml.dll");

        // 你之前列表里有个大写I的坑，如果文件名真的是大写I，就保持这样
        loadNative("llama.dll");

        loadNative("ggml-cpu-x64.dll"); // 尝试加载通用 x64 底层
        loadNative("ggml-vulkan.dll");  // 如果用显卡就加载这个

        System.out.println(">>> 依赖装载完毕，准备加载主桥接 DLL...");
        // 最后加载你的主入口 DLL
        loadNative("Java_org_argeo_jjml_ggml.dll");

        System.out.println(">>> 全部引擎启动成功！");
    }

    // 封装一个绝对路径加载方法，彻底无视 java.library.path
    private static void loadNative(String dllName) {
        try {
            String absolutePath = NATIVE_PATH + dllName;
            System.load(absolutePath);
            System.out.println("  [成功] " + dllName);
        } catch (UnsatisfiedLinkError e) {
            System.err.println("  [失败] " + dllName + " - " + e.getMessage());
        }
    }
}
