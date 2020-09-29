/**
 * Copyright © 2016-2020 The Thingsboard Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.thingsboard.server.service.install;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections.CollectionUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.queue.ProcessingStrategy;
import org.thingsboard.server.common.data.queue.ProcessingStrategyType;
import org.thingsboard.server.common.data.queue.Queue;
import org.thingsboard.server.common.data.queue.SubmitStrategy;
import org.thingsboard.server.common.data.queue.SubmitStrategyType;
import org.thingsboard.server.dao.dashboard.DashboardService;
import org.thingsboard.server.dao.queue.QueueService;
import org.thingsboard.server.service.install.sql.SqlDbHelper;
import org.thingsboard.server.service.queue.upgrade.TbQueueYmlRuleEngineSettings;

import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLSyntaxErrorException;
import java.sql.SQLWarning;
import java.sql.Statement;

import static org.thingsboard.server.service.install.DatabaseHelper.ADDITIONAL_INFO;
import static org.thingsboard.server.service.install.DatabaseHelper.ASSIGNED_CUSTOMERS;
import static org.thingsboard.server.service.install.DatabaseHelper.CONFIGURATION;
import static org.thingsboard.server.service.install.DatabaseHelper.CUSTOMER_ID;
import static org.thingsboard.server.service.install.DatabaseHelper.DASHBOARD;
import static org.thingsboard.server.service.install.DatabaseHelper.END_TS;
import static org.thingsboard.server.service.install.DatabaseHelper.ENTITY_ID;
import static org.thingsboard.server.service.install.DatabaseHelper.ENTITY_TYPE;
import static org.thingsboard.server.service.install.DatabaseHelper.ENTITY_VIEW;
import static org.thingsboard.server.service.install.DatabaseHelper.ENTITY_VIEWS;
import static org.thingsboard.server.service.install.DatabaseHelper.ID;
import static org.thingsboard.server.service.install.DatabaseHelper.KEYS;
import static org.thingsboard.server.service.install.DatabaseHelper.NAME;
import static org.thingsboard.server.service.install.DatabaseHelper.SEARCH_TEXT;
import static org.thingsboard.server.service.install.DatabaseHelper.START_TS;
import static org.thingsboard.server.service.install.DatabaseHelper.TENANT_ID;
import static org.thingsboard.server.service.install.DatabaseHelper.TITLE;
import static org.thingsboard.server.service.install.DatabaseHelper.TYPE;

@Service
@Profile("install")
@Slf4j
public class SqlDatabaseUpgradeService implements DatabaseEntitiesUpgradeService {

    private static final String SCHEMA_UPDATE_SQL = "schema_update.sql";

    @Value("${spring.datasource.url}")
    private String dbUrl;

    @Value("${spring.datasource.username}")
    private String dbUserName;

    @Value("${spring.datasource.password}")
    private String dbPassword;

    @Autowired
    private DashboardService dashboardService;

    @Autowired
    private InstallScripts installScripts;

    @Autowired
    private TbQueueYmlRuleEngineSettings ruleEngineSettings;

    @Autowired
    private QueueService queueService;

    @Autowired
    private SystemDataLoaderService systemDataLoaderService;

    @Override
    public void upgradeDatabase(String fromVersion) throws Exception {
        switch (fromVersion) {
            case "1.3.0":
                log.info("Updating schema ...");
                Path schemaUpdateFile = Paths.get(installScripts.getDataDir(), "upgrade", "1.3.1", SCHEMA_UPDATE_SQL);
                try (Connection conn = DriverManager.getConnection(dbUrl, dbUserName, dbPassword)) {
                    loadSql(schemaUpdateFile, conn);
                }
                log.info("Schema updated.");
                break;
            case "1.3.1":
                try (Connection conn = DriverManager.getConnection(dbUrl, dbUserName, dbPassword)) {

                    log.info("Dumping dashboards ...");
                    Path dashboardsDump = SqlDbHelper.dumpTableIfExists(conn, DASHBOARD,
                            new String[]{ID, TENANT_ID, CUSTOMER_ID, TITLE, SEARCH_TEXT, ASSIGNED_CUSTOMERS, CONFIGURATION},
                            new String[]{"", "", "", "", "", "", ""},
                            "tb-dashboards", true);
                    log.info("Dashboards dumped.");

                    log.info("Updating schema ...");
                    schemaUpdateFile = Paths.get(installScripts.getDataDir(), "upgrade", "1.4.0", SCHEMA_UPDATE_SQL);
                    loadSql(schemaUpdateFile, conn);
                    log.info("Schema updated.");

                    log.info("Restoring dashboards ...");
                    if (dashboardsDump != null) {
                        SqlDbHelper.loadTable(conn, DASHBOARD,
                                new String[]{ID, TENANT_ID, TITLE, SEARCH_TEXT, CONFIGURATION}, dashboardsDump, true);
                        DatabaseHelper.upgradeTo40_assignDashboards(dashboardsDump, dashboardService, true);
                        Files.deleteIfExists(dashboardsDump);
                    }
                    log.info("Dashboards restored.");
                }
                break;
            case "1.4.0":
                try (Connection conn = DriverManager.getConnection(dbUrl, dbUserName, dbPassword)) {
                    log.info("Updating schema ...");
                    schemaUpdateFile = Paths.get(installScripts.getDataDir(), "upgrade", "2.0.0", SCHEMA_UPDATE_SQL);
                    loadSql(schemaUpdateFile, conn);
                    log.info("Schema updated.");
                }
                break;
            case "2.0.0":
                try (Connection conn = DriverManager.getConnection(dbUrl, dbUserName, dbPassword)) {
                    log.info("Updating schema ...");
                    schemaUpdateFile = Paths.get(installScripts.getDataDir(), "upgrade", "2.1.1", SCHEMA_UPDATE_SQL);
                    loadSql(schemaUpdateFile, conn);
                    log.info("Schema updated.");
                }
                break;
            case "2.1.1":
                try (Connection conn = DriverManager.getConnection(dbUrl, dbUserName, dbPassword)) {

                    log.info("Dumping entity views ...");
                    Path entityViewsDump = SqlDbHelper.dumpTableIfExists(conn, ENTITY_VIEWS,
                            new String[]{ID, ENTITY_ID, ENTITY_TYPE, TENANT_ID, CUSTOMER_ID, TYPE, NAME, KEYS, START_TS, END_TS, SEARCH_TEXT, ADDITIONAL_INFO},
                            new String[]{"", "", "", "", "", "default", "", "", "0", "0", "", ""},
                            "tb-entity-views", true);
                    log.info("Entity views dumped.");

                    log.info("Updating schema ...");
                    schemaUpdateFile = Paths.get(installScripts.getDataDir(), "upgrade", "2.1.2", SCHEMA_UPDATE_SQL);
                    loadSql(schemaUpdateFile, conn);
                    log.info("Schema updated.");

                    log.info("Restoring entity views ...");
                    if (entityViewsDump != null) {
                        SqlDbHelper.loadTable(conn, ENTITY_VIEW,
                                new String[]{ID, ENTITY_ID, ENTITY_TYPE, TENANT_ID, CUSTOMER_ID, TYPE, NAME, KEYS, START_TS, END_TS, SEARCH_TEXT, ADDITIONAL_INFO}, entityViewsDump, true);
                        Files.deleteIfExists(entityViewsDump);
                    }
                    log.info("Entity views restored.");
                }
                break;
            case "2.1.3":
                try (Connection conn = DriverManager.getConnection(dbUrl, dbUserName, dbPassword)) {
                    log.info("Updating schema ...");
                    schemaUpdateFile = Paths.get(installScripts.getDataDir(), "upgrade", "2.2.0", SCHEMA_UPDATE_SQL);
                    loadSql(schemaUpdateFile, conn);
                    log.info("Schema updated.");
                }
                break;
            case "2.3.0":
                try (Connection conn = DriverManager.getConnection(dbUrl, dbUserName, dbPassword)) {
                    log.info("Updating schema ...");
                    schemaUpdateFile = Paths.get(installScripts.getDataDir(), "upgrade", "2.3.1", SCHEMA_UPDATE_SQL);
                    loadSql(schemaUpdateFile, conn);
                    log.info("Schema updated.");
                }
                break;
            case "2.3.1":
                try (Connection conn = DriverManager.getConnection(dbUrl, dbUserName, dbPassword)) {
                    log.info("Updating schema ...");
                    schemaUpdateFile = Paths.get(installScripts.getDataDir(), "upgrade", "2.4.0", SCHEMA_UPDATE_SQL);
                    loadSql(schemaUpdateFile, conn);
                    try {
                        conn.createStatement().execute("ALTER TABLE device ADD COLUMN label varchar(255)"); //NOSONAR, ignoring because method used to execute thingsboard database upgrade script
                    } catch (Exception e) {
                    }
                    log.info("Schema updated.");
                }
                break;
            case "2.4.1":
                try (Connection conn = DriverManager.getConnection(dbUrl, dbUserName, dbPassword)) {
                    log.info("Updating schema ...");
                    try {
                        conn.createStatement().execute("ALTER TABLE asset ADD COLUMN label varchar(255)"); //NOSONAR, ignoring because method used to execute thingsboard database upgrade script
                    } catch (Exception e) {
                    }
                    schemaUpdateFile = Paths.get(installScripts.getDataDir(), "upgrade", "2.4.2", SCHEMA_UPDATE_SQL);
                    loadSql(schemaUpdateFile, conn);
                    try {
                        conn.createStatement().execute("ALTER TABLE device ADD CONSTRAINT device_name_unq_key UNIQUE (tenant_id, name)"); //NOSONAR, ignoring because method used to execute thingsboard database upgrade script
                    } catch (Exception e) {
                    }
                    try {
                        conn.createStatement().execute("ALTER TABLE device_credentials ADD CONSTRAINT device_credentials_id_unq_key UNIQUE (credentials_id)"); //NOSONAR, ignoring because method used to execute thingsboard database upgrade script
                    } catch (Exception e) {
                    }
                    try {
                        conn.createStatement().execute("ALTER TABLE asset ADD CONSTRAINT asset_name_unq_key UNIQUE (tenant_id, name)"); //NOSONAR, ignoring because method used to execute thingsboard database upgrade script
                    } catch (Exception e) {
                    }
                    log.info("Schema updated.");
                }
                break;
            case "2.4.2":
                try (Connection conn = DriverManager.getConnection(dbUrl, dbUserName, dbPassword)) {
                    log.info("Updating schema ...");
                    try {
                        conn.createStatement().execute("ALTER TABLE alarm ADD COLUMN propagate_relation_types varchar"); //NOSONAR, ignoring because method used to execute thingsboard database upgrade script
                    } catch (Exception e) {
                    }
                    log.info("Schema updated.");
                }
                break;
            case "2.4.3":
                try (Connection conn = DriverManager.getConnection(dbUrl, dbUserName, dbPassword)) {
                    log.info("Updating schema ...");
                    try {
                        conn.createStatement().execute("ALTER TABLE attribute_kv ADD COLUMN json_v json;");
                    } catch (Exception e) {
                        if (e instanceof SQLSyntaxErrorException) {
                            try {
                                conn.createStatement().execute("ALTER TABLE attribute_kv ADD COLUMN json_v varchar(10000000);");
                            } catch (Exception e1) {
                            }
                        }
                    }
                    try {
                        conn.createStatement().execute("ALTER TABLE tenant ADD COLUMN isolated_tb_core boolean DEFAULT (false), ADD COLUMN isolated_tb_rule_engine boolean DEFAULT (false)");
                    } catch (Exception e) {
                    }
                    try {
                        long ts = System.currentTimeMillis();
                        conn.createStatement().execute("ALTER TABLE event ADD COLUMN ts bigint DEFAULT " + ts + ";"); //NOSONAR, ignoring because method used to execute thingsboard database upgrade script
                    } catch (Exception e) {
                    }
                    log.info("Schema updated.");
                }
                break;
            case "3.0.1":
                try (Connection conn = DriverManager.getConnection(dbUrl, dbUserName, dbPassword)) {
                    log.info("Updating schema ...");
                    if (isOldSchema(conn, 3000001)) {
                        String[] tables = new String[]{"admin_settings", "alarm", "asset", "audit_log", "attribute_kv",
                                "component_descriptor", "customer", "dashboard", "device", "device_credentials", "event",
                                "relation", "tb_user", "tenant", "user_credentials", "widget_type", "widgets_bundle",
                                "rule_chain", "rule_node", "entity_view"};
                        schemaUpdateFile = Paths.get(installScripts.getDataDir(), "upgrade", "3.0.1", "schema_update_to_uuid.sql");
                        loadSql(schemaUpdateFile, conn);

                        conn.createStatement().execute("call drop_all_idx()");

                        log.info("Optimizing alarm relations...");
                        conn.createStatement().execute("DELETE from relation WHERE relation_type_group = 'ALARM' AND relation_type <> 'ALARM_ANY';");
                        conn.createStatement().execute("DELETE from relation WHERE relation_type_group = 'ALARM' AND relation_type = 'ALARM_ANY' " +
                                "AND exists(SELECT * FROM alarm WHERE alarm.id = relation.to_id AND alarm.originator_id = relation.from_id)");
                        log.info("Alarm relations optimized.");

                        for (String table : tables) {
                            log.info("Updating table {}.", table);
                            Statement statement = conn.createStatement();
                            statement.execute("call update_" + table + "();");

                            SQLWarning warnings = statement.getWarnings();
                            if (warnings != null) {
                                log.info("{}", warnings.getMessage());
                                SQLWarning nextWarning = warnings.getNextWarning();
                                while (nextWarning != null) {
                                    log.info("{}", nextWarning.getMessage());
                                    nextWarning = nextWarning.getNextWarning();
                                }
                            }

                            conn.createStatement().execute("DROP PROCEDURE update_" + table);
                            log.info("Table {} updated.", table);
                        }
                        conn.createStatement().execute("call create_all_idx()");

                        conn.createStatement().execute("DROP PROCEDURE drop_all_idx");
                        conn.createStatement().execute("DROP PROCEDURE create_all_idx");
                        conn.createStatement().execute("DROP FUNCTION column_type_to_uuid");

                        log.info("Updating alarm relations...");
                        conn.createStatement().execute("UPDATE relation SET relation_type = 'ANY' WHERE relation_type_group = 'ALARM' AND relation_type = 'ALARM_ANY';");
                        log.info("Alarm relations updated.");

                        conn.createStatement().execute("UPDATE tb_schema_settings SET schema_version = 3001000;");

                        conn.createStatement().execute("VACUUM FULL");
                    }
                    log.info("Schema updated.");
                } catch (Exception e) {
                    log.error("Failed updating schema!!!", e);
                }
                break;
            case "3.1.0":
                try (Connection conn = DriverManager.getConnection(dbUrl, dbUserName, dbPassword)) {
                    log.info("Updating schema ...");
                    schemaUpdateFile = Paths.get(installScripts.getDataDir(), "upgrade", "3.1.0", SCHEMA_UPDATE_SQL);
                    loadSql(schemaUpdateFile, conn);
                    log.info("Schema updated.");
                }
                break;
            case "3.1.2":
                try (Connection conn = DriverManager.getConnection(dbUrl, dbUserName, dbPassword)) {
                    log.info("Updating schema ...");
                    try {
                        conn.createStatement().execute("CREATE TABLE IF NOT EXISTS queue ( " +
                                "id uuid NOT NULL CONSTRAINT queue_pkey PRIMARY KEY, " +
                                "created_time bigint NOT NULL, " +
                                "tenant_id uuid, " +
                                "name varchar(255), " +
                                "topic varchar(255), " +
                                "poll_interval int, " +
                                "partitions int, " +
                                "pack_processing_timeout bigint, " +
                                "submit_strategy varchar(255), " +
                                "processing_strategy varchar(255), " +
                                "CONSTRAINT queue_name_unq_key UNIQUE (tenant_id, name), " +
                                "CONSTRAINT queue_topic_unq_key UNIQUE (tenant_id, topic) " +
                                ");");
                    } catch (Exception e) {
                    }

                    try {
                        conn.createStatement().execute("ALTER TABLE tenant ADD COLUMN number_of_queues int, ADD COLUMN max_number_of_partitions_per_queue int");
                    } catch (Exception e) {
                    }

                    try {
                        conn.createStatement().execute("UPDATE tenant SET number_of_queues = 1, max_number_of_partitions_per_queue = 10 where isolated_tb_rule_engine = true");
                    } catch (Exception e) {
                    }

                    try {
                        if (!CollectionUtils.isEmpty(ruleEngineSettings.getQueues())) {
                            ruleEngineSettings.getQueues().forEach(queueSettings -> {
                                Queue queue = new Queue();
                                queue.setTenantId(TenantId.SYS_TENANT_ID);
                                queue.setName(queueSettings.getName());
                                queue.setTopic(queueSettings.getTopic());
                                queue.setPollInterval(queueSettings.getPollInterval());
                                queue.setPartitions(queueSettings.getPartitions());
                                queue.setPackProcessingTimeout(queueSettings.getPackProcessingTimeout());
                                SubmitStrategy submitStrategy = new SubmitStrategy();
                                submitStrategy.setBatchSize(queueSettings.getSubmitStrategy().getBatchSize());
                                submitStrategy.setType(SubmitStrategyType.valueOf(queueSettings.getSubmitStrategy().getType()));
                                queue.setSubmitStrategy(submitStrategy);
                                ProcessingStrategy processingStrategy = new ProcessingStrategy();
                                processingStrategy.setType(ProcessingStrategyType.valueOf(queueSettings.getProcessingStrategy().getType()));
                                processingStrategy.setRetries(queueSettings.getProcessingStrategy().getRetries());
                                processingStrategy.setFailurePercentage(queueSettings.getProcessingStrategy().getFailurePercentage());
                                processingStrategy.setPauseBetweenRetries(queueSettings.getProcessingStrategy().getPauseBetweenRetries());
                                queue.setProcessingStrategy(processingStrategy);
                                queueService.createOrUpdateQueue(queue);
                            });
                        } else {
                            systemDataLoaderService.createQueues();
                        }
                    } catch (Exception e) {
                    }
                    log.info("Schema updated.");
                }
                break;
            default:
                throw new RuntimeException("Unable to upgrade SQL database, unsupported fromVersion: " + fromVersion);
        }
    }

    private void loadSql(Path sqlFile, Connection conn) throws Exception {
        String sql = new String(Files.readAllBytes(sqlFile), Charset.forName("UTF-8"));
        conn.createStatement().execute(sql); //NOSONAR, ignoring because method used to execute thingsboard database upgrade script
        Thread.sleep(5000);
    }

    protected boolean isOldSchema(Connection conn, long fromVersion) {
        boolean isOldSchema = true;
        try {
            Statement statement = conn.createStatement();
            statement.execute("CREATE TABLE IF NOT EXISTS tb_schema_settings ( schema_version bigint NOT NULL, CONSTRAINT tb_schema_settings_pkey PRIMARY KEY (schema_version));");
            Thread.sleep(1000);
            ResultSet resultSet = statement.executeQuery("SELECT schema_version FROM tb_schema_settings;");
            if (resultSet.next()) {
                isOldSchema = resultSet.getLong(1) <= fromVersion;
            } else {
                resultSet.close();
                statement.execute("INSERT INTO tb_schema_settings (schema_version) VALUES (" + fromVersion + ")");
            }
            statement.close();
        } catch (InterruptedException | SQLException e) {
            log.info("Failed to check current PostgreSQL schema due to: {}", e.getMessage());
        }
        return isOldSchema;
    }
}
