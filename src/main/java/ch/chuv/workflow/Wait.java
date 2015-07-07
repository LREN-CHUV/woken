package ch.chuv.workflow;

import java.sql.*;

/**
 * Created by ludovic on 07.07.15.
 */
public class Wait {

    public static void main(String[] args) {
        Class<?> driver = org.postgresql.Driver.class;

        try {
            Connection connection =
                    DriverManager.getConnection(System.getenv("JDBC_URL"),
                            System.getenv("JDBC_USER"),
                            System.getenv("JDBC_PASSWORD"));

            CallableStatement countResultsQuery = connection.prepareCall(System.getenv("COUNT_RESULTS_QUERY"));
            int expectedCount = Integer.parseInt(System.getenv("EXPECTED_COUNT_RESULTS"));
            int realCount = -1;

            do {
                ResultSet resultSet = countResultsQuery.executeQuery();
                if (resultSet.next()) {
                    realCount = resultSet.getInt(0);
                }
                if (realCount != expectedCount) {
                    Thread.sleep(1000);
                }
            } while (realCount != expectedCount);

        } catch (Exception e) {
            e.printStackTrace();
        }

    }
}
