package android.content;

public class ContextWrapper extends Context {
    protected final Context base;
    public ContextWrapper(Context base) { this.base = base; }
    @Override public SharedPreferences getSharedPreferences(String name, int mode) {
        if (base == null) throw new IllegalStateException("No base Context");
        return base.getSharedPreferences(name, mode);
    }
}
