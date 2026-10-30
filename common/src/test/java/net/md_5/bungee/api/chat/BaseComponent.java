package net.md_5.bungee.api.chat;

/**
 * 测试运行期占位：Paper API 的 {@code CommandSender}/{@code Entity} 方法签名引用了 BungeeCord 的聊天类，
 * 而单元测试类路径里没有 BungeeCord 库。{@link java.lang.reflect.Proxy} 枚举接口方法时会去解析这些类型，
 * 缺了就会 {@code NoClassDefFoundError}。这里只放最小占位，让代理能创建；测试从不调用这些方法。
 */
public class BaseComponent {
}
