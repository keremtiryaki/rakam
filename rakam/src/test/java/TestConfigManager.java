import com.google.common.collect.HashBasedTable;
import com.google.common.collect.Table;
import org.rakam.analysis.ConfigManager;

public class TestConfigManager implements ConfigManager {
    Table<String, String, Object> table;

    public TestConfigManager() {
        this.table = HashBasedTable.create();
    }

    @Override
    public synchronized  <T> T getConfig(String project, String configName, Class<T> clazz) {
        return (T) table.get(project, configName);
    }

    @Override
    public synchronized  <T> void setConfig(String project, String configName, T clazz) {
        table.put(project, configName, clazz);
    }

    @Override
    public synchronized <T> T setConfigOnce(String project, String configName, T clazz) {
        table.column(project).putIfAbsent(configName, clazz);
        return null;
    }

    @Override
    public void clear()
    {
        table.clear();
    }
}
