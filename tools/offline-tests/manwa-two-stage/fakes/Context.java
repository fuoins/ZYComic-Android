package android.content;

public abstract class Context {
    public static final int MODE_PRIVATE = 0;
    public Context() {}
    public Context getApplicationContext() { return this; }
    public abstract SharedPreferences getSharedPreferences(String name, int mode);
}
