# 本工程无反射、无序列化框架、无 JNI，默认规则即可。
# 仅保留 View 的构造函数（布局 inflate 不走这里，但自定义 View 从 XML 实例化时需要）。
-keepclassmembers class * extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
}
