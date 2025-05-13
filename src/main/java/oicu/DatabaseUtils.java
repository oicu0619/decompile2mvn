package oicu;

import lombok.SneakyThrows;

import java.nio.file.Path;
import java.sql.*;

public class DatabaseUtils {
    private static final String dependencies = """
            CREATE TABLE DEPENDENCIES (
                id INTEGER AUTO_INCREMENT PRIMARY KEY,
                group_id TEXT,
                artifact_id TEXT,
                version TEXT,
                hash TEXT NOT NULL,
                repo TEXT
            )""";

    private static final String[][] expectedSchema = {
            {"id", "INTEGER", "NO"},
            {"group_id", "CHARACTER VARYING", "YES"},
            {"artifact_id", "CHARACTER VARYING", "YES"},
            {"version", "CHARACTER VARYING", "YES"},
            {"hash", "CHARACTER VARYING", "NO"},
            {"repo", "CHARACTER VARYING", "YES"},
    };

    @SneakyThrows
    public static boolean isDependencyTableCorrect(Connection conn) {
            String tableName = "DEPENDENCIES";
            DatabaseMetaData metaData = conn.getMetaData();
            ResultSet rs = metaData.getColumns(null, null, tableName, null);
            int index = 0;
            while (rs.next()) {
                if (index >= expectedSchema.length) {
                    System.err.printf("Schema check failed: Table '%s' has more columns than expected (%d). Found column '%s'.%n",
                            tableName, expectedSchema.length, rs.getString("COLUMN_NAME"));
                    return false;
                }
                String colName = rs.getString("COLUMN_NAME");
                String colType = rs.getString("TYPE_NAME"); // Standard JDBC metadata column
                String nullableStr = rs.getString("IS_NULLABLE"); // Returns "YES", "NO", or ""
                String nullable = (nullableStr == null) ? "" : nullableStr;
                if (!colName.equalsIgnoreCase(expectedSchema[index][0])) {
                    System.err.printf("Schema check failed for table '%s': Column %d name mismatch. DB='%s', Expected='%s'%n",
                            tableName, index + 1, colName, expectedSchema[index][0]);
                    return false;
                }
                if (!colType.equalsIgnoreCase(expectedSchema[index][1])) {
                    System.err.printf("Schema check failed for table '%s': Column '%s' type mismatch. DB='%s', Expected='%s'%n",
                            tableName, colName, colType, expectedSchema[index][1]);
                    return false;
                }
                if (!nullable.equalsIgnoreCase(expectedSchema[index][2])) {
                    System.err.printf("Schema check failed for table '%s': Column '%s' nullability mismatch. DB='%s', Expected='%s'%n",
                            tableName, colName, nullable, expectedSchema[index][2]);
                    return false;
                }
                index++;
            }
            if (index != expectedSchema.length) {
                System.err.printf("Schema check failed: Table '%s' has fewer columns (%d) than expected (%d).%n",
                        tableName, index, expectedSchema.length);
                return false;
            }
            return true;
    }


    public static Connection createDbConnection(Path databasePath) throws ClassNotFoundException, SQLException {
        Class.forName("org.h2.Driver");
        Connection connection = DriverManager.getConnection("jdbc:h2:" + databasePath.toAbsolutePath() + ";AUTO_SERVER=TRUE;AUTO_RECONNECT=TRUE");
        return connection;
    }

    @SneakyThrows
    public static void checkDatabase(Connection dbConn) {
            boolean tableExists = false;
            boolean schemaCorrect = false;
            try (PreparedStatement stmt = dbConn.prepareStatement(
                    "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = 'DEPENDENCIES'")) {
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        tableExists = rs.getInt(1) > 0;
                    }
                }
            }
            if (tableExists) {
                schemaCorrect = isDependencyTableCorrect(dbConn);
            }

            if (tableExists && !schemaCorrect) {
                System.out.println("Table exists but schema is wrong — dropping it.");
                try (Statement stmt = dbConn.createStatement()) {
                    stmt.execute("DROP TABLE DEPENDENCIES");
                }
                tableExists = false; // After dropping
            }

            if (!tableExists) {
                System.out.println("Creating table dependencies...");
                try (Statement stmt = dbConn.createStatement()) {
                    stmt.execute(dependencies);
                }
            } else {
                System.out.println("Table exists and schema is correct.");
            }
    }

    @SneakyThrows
    public static void storeDependencyInDb(Dependency dependency, Connection dbConn) {
        while (true) {
            try (PreparedStatement pstmt = dbConn.prepareStatement("INSERT INTO DEPENDENCIES (group_id, artifact_id, version, hash, repo) VALUES (?, ?, ?, ? ,?)")) {
                pstmt.setString(1, dependency.getVerifiedGroupId());
                pstmt.setString(2, dependency.getVerifiedArtifactId());
                pstmt.setString(3, dependency.getVerifiedVersion());
                pstmt.setString(4, dependency.hash);
                pstmt.setString(5, dependency.repo);
                pstmt.executeUpdate();
                return;
            } 
        }
    }


    @SneakyThrows
    public static boolean getDependencyInDb(Dependency dependency, Connection dbConn) {
        try (PreparedStatement stmt = dbConn.prepareStatement("SELECT group_id, artifact_id, version, hash, repo FROM DEPENDENCIES WHERE hash = ? LIMIT 1")){
            stmt.setString(1, dependency.hash);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                dependency.hash = rs.getString("hash");
                dependency.repo = rs.getString("repo");
                dependency.unverifiedGroupId = rs.getString("group_id");
                dependency.unverifiedArtifactId = rs.getString("artifact_id");
                dependency.unverifiedVersion = rs.getString("version");
                rs.close();
                return true;
            }
            rs.close();
            return false;
        }
    }
}

