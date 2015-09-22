package ch.chuv.workflow;

import java.sql.*;

/**
 * Created by ludovic on 07.07.15.
 */
public class Wait {

    public static void main(String[] args) {
        try {
            Class<?> driver = Class.forName(System.getenv("JDBC_DRIVER"));
            Connection connection =
                    DriverManager.getConnection(System.getenv("JDBC_URL"),
                            System.getenv("JDBC_USER"),
                            System.getenv("JDBC_PASSWORD"));

            PreparedStatement countResultsQuery = connection.prepareStatement(System.getenv("COUNT_RESULTS_QUERY"));
            int expectedCount = Integer.parseInt(System.getenv("EXPECTED_COUNT_RESULTS"));
            int realCount = -1;

            do {
                ResultSet resultSet = countResultsQuery.executeQuery();
                if (resultSet.next()) {
                    realCount = resultSet.getInt(1);
                }
                System.out.println("Found " + realCount + " results");
                if (realCount != expectedCount) {
                    Thread.sleep(1000);
                }
            } while (realCount != expectedCount);

        } catch (Exception e) {
            e.printStackTrace();
        }

    }
}
