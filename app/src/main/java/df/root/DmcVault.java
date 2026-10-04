package df.root;

/** Read-only access to Samsung's VaultKeeper DMC vault (the bootloader
 *  flash-lock state) via the VaultKeeperManager system service. Non-Samsung
 *  devices report unsupported. Based on polygraphene/DFReroot's DmcVault. */
public final class DmcVault {

    public static final class Result {
        public final boolean supported;
        public final int lock, maint, at;
        public final boolean odinAllowed;
        public final String detail;

        Result(boolean supported, int lock, int maint, int at,
               boolean odinAllowed, String detail) {
            this.supported = supported;
            this.lock = lock;
            this.maint = maint;
            this.at = at;
            this.odinAllowed = odinAllowed;
            this.detail = detail;
        }
    }

    private static Result unsupported(String detail) {
        return new Result(false, 0, 0, 0, false, detail);
    }

    /** Odin flashing counts as allowed when the device is unlocked, in
     *  maintenance mode, or the always-allow flag is set. */
    public static Result read() {
        Class<?> c;
        try {
            c = Class.forName("com.samsung.android.service.vaultkeeper.VaultKeeperManager");
        } catch (Throwable t) {
            return unsupported("no VaultKeeper (" + t.getClass().getSimpleName() + ")");
        }
        try {
            Object inst = c.getMethod("getInstance", String.class).invoke(null, "DMC");
            if (inst == null) return unsupported("no DMC instance");
            Object raw = c.getMethod("read", int.class).invoke(inst, 1);
            if (!(raw instanceof byte[])) return unsupported("DMC read failed");
            byte[] data = (byte[]) raw;
            if (data.length != 32) return unsupported("DMC bad length " + data.length);
            int lock = data[0] & 0xFF, maint = data[1] & 0xFF, at = data[2] & 0xFF;
            boolean odinAllowed = lock == 0 || maint == 1 || at == 1;
            return new Result(true, lock, maint, at, odinAllowed, null);
        } catch (Throwable t) {
            String msg = t.getClass().getSimpleName()
                    + (t.getMessage() != null ? ": " + t.getMessage() : "");
            return unsupported(msg);
        }
    }

    private DmcVault() {}
}
