package org.rakam.ui;

import com.google.auto.service.AutoService;
import com.google.common.base.Optional;
import com.google.common.eventbus.Subscribe;
import com.google.inject.Binder;
import com.google.inject.Provider;
import com.google.inject.Scopes;
import com.google.inject.multibindings.Multibinder;
import com.google.inject.multibindings.OptionalBinder;
import com.google.inject.name.Named;
import com.google.inject.name.Names;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.rakam.analysis.CustomParameter;
import org.rakam.analysis.JDBCPoolDataSource;
import org.rakam.config.EncryptionConfig;
import org.rakam.config.JDBCConfig;
import org.rakam.plugin.InjectionHook;
import org.rakam.plugin.RakamModule;
import org.rakam.plugin.stream.EventStreamConfig;
import org.rakam.plugin.user.UserPluginConfig;
import org.rakam.report.eventexplorer.EventExplorerConfig;
import org.rakam.report.realtime.RealTimeConfig;
import org.rakam.server.http.HttpRequestHandler;
import org.rakam.server.http.HttpService;
import org.rakam.ui.UIEvents.ProjectCreatedEvent;
import org.rakam.ui.UIPermissionParameterProvider.Project;
import org.rakam.ui.customreport.CustomPageHttpService;
import org.rakam.ui.customreport.CustomReport;
import org.rakam.ui.customreport.CustomReportHttpService;
import org.rakam.ui.customreport.CustomReportMetadata;
import org.rakam.ui.customreport.JDBCCustomReportMetadata;
import org.rakam.ui.page.CustomPageDatabase;
import org.rakam.ui.page.FileBackedCustomPageDatabase;
import org.rakam.ui.page.JDBCCustomPageDatabase;
import org.rakam.ui.report.Report;
import org.rakam.ui.report.ReportHttpService;
import org.rakam.ui.report.UIRecipeHttpService;
import org.rakam.ui.user.UserSubscriptionHttpService;
import org.rakam.ui.user.WebUserHttpService;
import org.rakam.util.ConditionalModule;
import org.rakam.util.NotFoundHandler;
import org.skife.jdbi.v2.DBI;
import org.skife.jdbi.v2.Handle;
import org.skife.jdbi.v2.util.IntegerMapper;

import javax.inject.Inject;

import java.util.List;
import java.util.Map;

import static io.airlift.configuration.ConfigBinder.configBinder;

@ConditionalModule(config = "ui.enable", value = "true")
@AutoService(RakamModule.class)
public class RakamUIModule
        extends RakamModule
{
    @Override
    protected void setup(Binder binder)
    {
        configBinder(binder).bindConfig(EncryptionConfig.class);
        configBinder(binder).bindConfig(UserPluginConfig.class);
        configBinder(binder).bindConfig(RealTimeConfig.class);
        configBinder(binder).bindConfig(EventStreamConfig.class);
        configBinder(binder).bindConfig(EventExplorerConfig.class);
        configBinder(binder).bindConfig(UserPluginConfig.class);

        RakamUIConfig rakamUIConfig = buildConfigObject(RakamUIConfig.class);

        OptionalBinder.newOptionalBinder(binder, DashboardService.class);
        OptionalBinder.newOptionalBinder(binder, ReportMetadata.class);
        OptionalBinder.newOptionalBinder(binder, CustomPageDatabase.class);
        OptionalBinder.newOptionalBinder(binder, CustomReportMetadata.class);

        if (rakamUIConfig.getCustomPageBackend() != null) {
            switch (rakamUIConfig.getCustomPageBackend()) {
                case FILE:
                    binder.bind(FileBackedCustomPageDatabase.class).in(Scopes.SINGLETON);
                    break;
                case JDBC:
                    binder.bind(JDBCCustomPageDatabase.class).in(Scopes.SINGLETON);
                    break;
            }
        }

        Multibinder<CustomParameter> customParameters = Multibinder.newSetBinder(binder, CustomParameter.class);
        customParameters.addBinding().toProvider(UIPermissionParameterProvider.class);

        binder.bind(JDBCPoolDataSource.class)
                .annotatedWith(Names.named("ui.metadata.jdbc"))
                .toInstance(JDBCPoolDataSource.getOrCreateDataSource(buildConfigObject(JDBCConfig.class, "ui.metadata.jdbc")));

        Multibinder<InjectionHook> hooks = Multibinder.newSetBinder(binder, InjectionHook.class);
        hooks.addBinding().to(DatabaseScript.class);

        binder.bind(FlywayExecutor.class).asEagerSingleton();

        binder.bind(ProjectDeleteEventListener.class).asEagerSingleton();
        binder.bind(ReportMetadata.class).to(JDBCReportMetadata.class).in(Scopes.SINGLETON);
        binder.bind(CustomReportMetadata.class).to(JDBCCustomReportMetadata.class).in(Scopes.SINGLETON);
        binder.bind(DefaultDashboardCreator.class).asEagerSingleton();

        Multibinder<HttpService> httpServices = Multibinder.newSetBinder(binder, HttpService.class);
        httpServices.addBinding().to(WebUserHttpService.class);
        httpServices.addBinding().to(ReportHttpService.class);
        httpServices.addBinding().to(DashboardService.class);
        httpServices.addBinding().to(CustomReportHttpService.class);
        httpServices.addBinding().to(CustomPageHttpService.class);
        httpServices.addBinding().to(ProxyWebService.class);
        httpServices.addBinding().to(WebHookUIHttpService.class);
        httpServices.addBinding().to(ScheduledTaskUIHttpService.class);
        httpServices.addBinding().to(CustomEventMapperUIHttpService.class);
        httpServices.addBinding().to(ClusterService.class);
        httpServices.addBinding().to(UIRecipeHttpService.class);
        httpServices.addBinding().to(ScheduledEmailService.class);
        if (rakamUIConfig.getStripeKey() != null) {
            httpServices.addBinding().to(UserSubscriptionHttpService.class);
        }
        httpServices.addBinding().to(RakamUIWebService.class);

        binder.bind(HttpRequestHandler.class)
                .annotatedWith(NotFoundHandler.class)
                .toProvider(WebsiteRequestHandler.class);
    }

    @Override
    public String name()
    {
        return "Web Interface for Rakam APIs";
    }

    @Override
    public String description()
    {
        return "Can be used as a BI tool and a tool that allows you to create your customized analytics service frontend.";
    }

    public enum CustomPageBackend
    {
        FILE, JDBC
    }

    public static class DefaultDashboardCreator
    {

        private final DashboardService service;
        private final DBI dbi;

        @Inject
        public DefaultDashboardCreator(DashboardService service, @javax.inject.Named("ui.metadata.jdbc") JDBCPoolDataSource dataSource)
        {
            this.service = service;
            this.dbi = new DBI(dataSource);
        }

        @Subscribe
        public void onCreateProject(ProjectCreatedEvent event)
        {
            Integer id;
            try (Handle handle = dbi.open()) {
                id = handle.createQuery("select user_id from web_user_project where id = :id")
                        .bind("id", event.project).map(IntegerMapper.FIRST).first();
            }
            Project project = new Project(event.project, id);
            DashboardService.Dashboard dashboard = service.create(project, "My dashboard", true, null);
            service.setDefault(project, dashboard.id);
        }
    }

    public static class ProjectDeleteEventListener
    {
        private final DashboardService dashboardService;
        private final CustomPageDatabase customPageDatabase;
        private final ReportMetadata reportMetadata;
        private final CustomReportMetadata customReportMetadata;

        @Inject
        public ProjectDeleteEventListener(DashboardService dashboardService,
                Optional<CustomPageDatabase> customPageDatabase,
                ReportMetadata reportMetadata,
                CustomReportMetadata customReportMetadata)
        {
            this.reportMetadata = reportMetadata;
            this.customReportMetadata = customReportMetadata;
            this.customPageDatabase = customPageDatabase.orNull();
            this.dashboardService = dashboardService;
        }

        @Subscribe
        public void onDeleteProject(UIEvents.ProjectDeletedEvent event)
        {
            for (DashboardService.Dashboard dashboard : dashboardService.list(new Project(0, event.project)).dashboards) {
                dashboardService.delete(new Project(event.project, 0), dashboard.id);
            }
            if (customPageDatabase != null) {
                for (CustomPageDatabase.Page page : customPageDatabase.list(event.project)) {
                    customPageDatabase.delete(event.project, page.slug);
                }
            }
            for (Report report : reportMetadata.getReports(null, event.project)) {
                reportMetadata.delete(null, event.project, report.slug);
            }
            for (Map.Entry<String, List<CustomReport>> types : customReportMetadata.list(event.project).entrySet()) {
                for (CustomReport customReport : types.getValue()) {
                    customReportMetadata.delete(types.getKey(), event.project, customReport.name);
                }
            }
        }
    }

    public static class DatabaseScript
            implements InjectionHook
    {
        private final DBI dbi;
        private final RakamUIConfig config;

        @Inject
        public DatabaseScript(@Named("ui.metadata.jdbc") JDBCPoolDataSource dataSource, RakamUIConfig config)
        {
            dbi = new DBI(dataSource);
            this.config = config;
        }

        @Override
        public void call()
        {
            try (Handle handle = dbi.open()) {

                handle.createStatement("CREATE TABLE IF NOT EXISTS web_user (" +
                        "  id SERIAL PRIMARY KEY,\n" +
                        "  email TEXT NOT NULL UNIQUE,\n" +
                        "  is_activated BOOLEAN DEFAULT false NOT NULL,\n" +
                        "  password TEXT,\n" +
                        "  name TEXT,\n" +
                        "  created_at TIMESTAMP NOT NULL\n" +
                        "  )")
                        .execute();

                handle.createStatement("CREATE TABLE IF NOT EXISTS web_user_project (\n" +
                        "  id serial NOT NULL,\n" +
                        "  project varchar(150) NOT NULL,\n" +
                        "  api_url varchar(250),\n" +
                        "  user_id INT REFERENCES web_user(id),\n" +
                        "  created_at timestamp DEFAULT now() NOT NULL,\n" +
                        "  CONSTRAINT project_check UNIQUE(project, api_url, user_id),\n" +
                        "  PRIMARY KEY (id, user_id)\n" +
                        ")")
                        .execute();

                handle.createStatement("CREATE TABLE IF NOT EXISTS web_user_api_key (" +
                        "  id SERIAL NOT NULL,\n" +
                        "  project_id INTEGER REFERENCES web_user_project(id),\n" +
                        "  scope_expression TEXT,\n" +
                        "  user_id INT REFERENCES web_user(id),\n" +
                        "  write_key TEXT,\n" +
                        "  read_key TEXT,\n" +
                        "  master_key TEXT,\n" +
                        "  created_at timestamp DEFAULT now() NOT NULL," +
                        "  PRIMARY KEY (id)\n" +
                        "  )")
                        .execute();

                handle.createStatement("CREATE TABLE IF NOT EXISTS web_user_api_key_permission (\n" +
                        " api_key_id int4 NOT NULL,\n" +
                        " user_id int4 NOT NULL,\n" +
                        " read_permission boolean not null,\n" +
                        " write_permission boolean not null,\n" +
                        " master_permission boolean not null,\n" +
                        " scope_expression text,\n" +
                        " created_at timestamp NOT NULL DEFAULT now(),\n" +
                        " PRIMARY KEY (api_key_id, user_id),\n" +
                        " FOREIGN KEY (user_id) REFERENCES web_user (id),\n" +
                        " FOREIGN KEY (api_key_id) REFERENCES web_user_api_key (id)\n" +
                        ")")
                        .execute();

                handle.createStatement("CREATE TABLE IF NOT EXISTS reports (" +
                        "  project_id INT REFERENCES web_user_project(id) ON UPDATE NO ACTION ON DELETE CASCADE," +
                        "  user_id INT REFERENCES web_user(id)," +
                        "  slug VARCHAR(255) NOT NULL," +
                        "  category VARCHAR(255)," +
                        "  name VARCHAR(255) NOT NULL," +
                        "  query TEXT NOT NULL," +
                        "  options TEXT," +
                        "  shared BOOLEAN NOT NULL DEFAULT false," +
                        "  created_at TIMESTAMP NOT NULL DEFAULT now()," +
                        "  CONSTRAINT address UNIQUE(project_id, slug)" +
                        "  )")
                        .execute();

                handle.createStatement("CREATE TABLE IF NOT EXISTS custom_reports (" +
                        "  report_type VARCHAR(255) NOT NULL," +
                        "  user_id INT REFERENCES web_user(id)," +
                        "  project_id INT REFERENCES web_user_project(id) ON UPDATE NO ACTION ON DELETE CASCADE," +
                        "  name VARCHAR(255) NOT NULL," +
                        "  data TEXT NOT NULL," +
                        "  PRIMARY KEY (project_id, report_type, name)" +
                        "  )")
                        .execute();

                handle.createStatement("CREATE TABLE IF NOT EXISTS rakam_cluster (" +
                        "  user_id INT REFERENCES web_user(id)," +
                        "  api_url VARCHAR(255) NOT NULL," +
                        "  lock_key VARCHAR(255)," +
                        "  PRIMARY KEY (user_id, api_url)" +
                        "  )")
                        .execute();

                handle.createStatement("CREATE TABLE IF NOT EXISTS dashboard (" +
                        "  id SERIAL," +
                        "  project_id INT REFERENCES web_user_project(id) ON UPDATE NO ACTION ON DELETE CASCADE," +
                        "  user_id INT REFERENCES web_user(id)," +
                        "  name VARCHAR(255) NOT NULL," +
                        "  options TEXT," +
                        "  UNIQUE (project_id, name)," +
                        "  PRIMARY KEY (id)" +
                        "  )")
                        .execute();
                handle.createStatement("CREATE TABLE IF NOT EXISTS dashboard_items (" +
                        "  id SERIAL," +
                        "  dashboard int NOT NULL REFERENCES dashboard(id) ON DELETE CASCADE," +
                        "  name VARCHAR(255) NOT NULL," +
                        "  directive VARCHAR(255) NOT NULL," +
                        "  data TEXT NOT NULL," +
                        "  PRIMARY KEY (id)" +
                        "  )")
                        .execute();

                if (config.getCustomPageBackend() == CustomPageBackend.JDBC) {
                    handle.createStatement("CREATE TABLE IF NOT EXISTS custom_page (" +
                            "  project_id INT REFERENCES web_user_project(id) ON UPDATE NO ACTION ON DELETE CASCADE," +
                            "  name VARCHAR(255) NOT NULL," +
                            "  user_id INT REFERENCES web_user(id)," +
                            "  slug VARCHAR(255) NOT NULL," +
                            "  category VARCHAR(255)," +
                            "  data TEXT NOT NULL," +
                            "  PRIMARY KEY (project_id, slug)" +
                            "  )")
                            .execute();
                }
            }
        }
    }

    public static class FlywayExecutor
    {
        @Inject
        public FlywayExecutor(@Named("ui.metadata.jdbc") JDBCConfig config)
        {
            Flyway flyway = new Flyway();
            flyway.setBaselineOnMigrate(true);
            flyway.setLocations("db/migration/ui");
            flyway.setTable("schema_version_ui");
            flyway.setDataSource(config.getUrl(), config.getUsername(), config.getPassword());
            try {
                flyway.migrate();
            }
            catch (FlywayException e) {
                flyway.repair();
            }
        }
    }

    public static class WebsiteRequestHandler
            implements Provider<HttpRequestHandler>
    {

        private final RakamUIWebService service;

        @Inject
        public WebsiteRequestHandler(RakamUIConfig config)
        {

            service = new RakamUIWebService(config);
        }

        @Override
        public HttpRequestHandler get()
        {
            return service::main;
        }
    }
}
