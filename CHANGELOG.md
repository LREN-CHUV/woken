
# Changelog


## (https://github.com/LREN-CHUV/woken/compare/2.8.0...2.9.0)[2.9.0] - 2019-01-22

* Update algorithms
   * hbpmip/python-anova:0.4.5
   * hbpmip/python-correlation-heatmap:0.5.0
   * hbpmip/python-histograms:0.6.2
   * hbpmip/python-summary-statistics:0.4.1
   * hbpmip/python-sgd-linear-model:0.3.4
   * hbpmip/python-sgd-naive-bayes:0.3.4
   * hbpmip/python-sgd-neural-network:0.3.4
   * hbpmip/python-gradient-boosting:0.3.4
   * hbpmip/python-correlation-heatmap:0.5.1
   * hbpmip/python-distributed-pca:0.5.1
   * hbpmip/python-distributed-kmeans:0.2.2
   * hbpmip/python-tsne:0.4.3
   * hbpmip/r-ggparci:0.2.2
   * hbpmip/python-jsi-hinmine:0.3.1
   * hbpmip/python-jsi-hedwig:1.0.9
* Update Woken-validation to 2.6.0
* API: /cluster - expose Akka management checks
* SPI: Describe tables in the configuration
* Do not always require covariables to exist for distributed queries
* Stream support for websocket client
* Better descriptions of algorithms
* Improve shutdown of Akka services in the cluster
* Add validation checks for feature tables
* Validation of experiment queries
* Quote all column and table names in generated SQL, table and column names are now case-sensitive and can be non standard identifiers
* Monitoring: add Akka management
* Monitoring: use sup library for functional-oriented definition of health checks.
* Monitoring: Add health checks for cluster status, database status, execution of Docker algorithms with Chronos/Mesos, delegation of computations to Woken validation workers
* Self checks for Akka server and web server
* __dev__ Remove cyclic dependencies in code and add tests against cyclic dependencies
* __dev__ Refactoring database access to features
* __dev__ Heavy refactoring of internal services, wrap them into Resource to ensure orderly startup and cleanup of the services
* __dev__ Reorganisation of packages
* __dev__ Reify several concepts: TableId, FeaturesTableDescription, backends for WokenWorker and AlgorithmExecutor
* __dev__ Use gen_features_table_seq defined in Woken db
* __dev__ Update many library dependencies, including Akka 2.5.19, Cats 1.5.0
* __dev__ Update woken-messages to 2.9.1
* __test__ Test mining queries
* __test__ Update and fix integration tests
* __test__ Add unit tests for FeaturesTableRepositoryDAO and ExtendedFeaturesTableRepositoryDAO
* __fix__ Fix validation of tables at startup
* __fix__ Fix startup of services
* __fix__ Refactor and fix cross-validation: use a temporary table in Postgres to create the random k-folds
* __fix__ Fix filtering queries

## (https://github.com/LREN-CHUV/woken/compare/2.7.0...2.8.0)[2.8.0] - 2018-05-23

* New algorithms
   * hbpmip/python-distributed-kmeans:0.2.1 distributed
* Update algorithms
   * hbpmip/python-anova:0.4.4
   * hbpmip/python-linear-regression:0.3.1 distributed
   * hbpmip/python-summary-statistics:0.4.0 distributed
   * hbpmip/python-histograms:0.6.1
   * hbpmip/python-knn:0.4.0
   * hbpmip/python-distributed-kmeans:0.2.0
   * hbpmip/python-sgd-linear-model:0.3.3
   * hbpmip/python-sgd-naive-bayes:0.3.3
   * hbpmip/python-sgd-neural-network:0.3.3
   * hbpmip/python-gradient-boosting:0.3.3
   * hbpmip/python-distributed-pca:0.4.0 distributed
   * hbpmip/python-correlation-heatmap:0.4.0
* Update woken-validation to 2.5.3
* Enable distributed support for histograms, statistics summary, kmeans
* HinMine and Heatmaply algorithms considered now ready for production
* Distributed algorithms: support map-reduce
* Provenance: keep track of intial query in the response
* Monitoring: trace actions in actors
* Select only covariables and groupings known locally
* Exchange large messages between Woken nodes using streaming messages over websockets
* __dev__ Introduce distributedExecutionPlan
* __dev__ Update woken-messages to 2.8.1
* __test__ Integration tests for HinMine and Heatmaply
* __test__ Integration tests for distributed algorithms in a federation
* __fix__ Report errors on websockets, queries
* __fix__ Improve execution of remote validations in an experiment
* __fix__ Fix more http connection leaks on Chronos client
* __fix__ Hedwig should work for nominal and numerical covariables
* __fix__ improve shuffling during K-fold cross validation
* __fix__ Fix query for cross validation

## (https://github.com/LREN-CHUV/woken/compare/2.6.0...2.7.0)[2.7.0] - 2018-05-15

* New algorithms
   * hbpmip/python-distributed-pca:0.3.1
* Update algorithms
   * hbpmip/python-histograms:0.5.1
   * hbpmip/python-summary-statistics:0.3.4
   * hbpmip/python-anova:0.4.3
   * hbpmip/python-linear-regression:0.2.2
   * hbpmip/python-sgd-linear-model:0.3.0
   * hbpmip/python-sgd-naive-bayes:0.3.0
   * hbpmip/python-sgd-neural-network:0.3.0
   * hbpmip/python-gradient-boosting:0.3.0
   * hbpmip/python-knn:0.3.1
   * hbpmip/python-correlation-heatmap:0.3.1
   * hbpmip/python-tsne:0.4.2
   * hbpmip/python-jsi-hedwig:1.0.7
* Update woken-validation to 2.5.0
* Handle remote validation requests in an experiment
* Stabilisation: Use DistPubSub to communicate with Woken validation
* Update Linear regression algo to support logistic regression on nominal variables
* __dev__ Update Woken-messages to 2.7.5
* __fix__ Fix type in algo spec
* __fix__ Improve labels and parameters for algorithms
* __fix__ Remove HinMine as it crashes Woken
* __fix__ Change type for algo ggparci to features_extraction
* __fix__ Naive Bayes: configure only for classification

## (https://github.com/LREN-CHUV/woken/compare/2.5.0...2.6.0)[2.6.0] - 2018-05-01

* Update algorithms
   * hbpmip/python-anova:0.4.2
   * hbpmip/python-histograms:0.5.0
   * hbpmip/python-knn:0.3.0
   * hbpmip/python-linear-regression:0.2.0
   * hbpmip/python-summary-statistics:0.3.2
   * hbpmip/python-correlation-heatmap:0.1.5
   * hbpmip/python-sgd-linear-model:0.2.0
   * hbpmip/python-sgd-naive-bayes:0.2.0
   * hbpmip/python-sgd-neural-network:0.2.0
   * hbpmip/python-gradient-boosting:0.2.0
   * hbpmip/python-tsne:0.4.1
   * hbpmip/python-jsi-hinmine:0.2.3
   * hbpmip/python-jsi-hedwig:1.0.5
   * hbpmip/r-ggparci:0.2.1
* Update woken-validation to 2.4.11
* SPI: easy switch between Netty and Artery remoting
* SPI: Remove deprecated PARAM_MODEL_x env vars
* SPI: Configuration for CPU and memory allocated to a job
* SPI: Rename config for defaultJobMemory and Cpus
* Monitoring: Add dead letters monitor
* Use Artery TCP for Akka remoting
* Improve constraints for linear regression algorithm
* Define parameters for new algorithms
* Align config with Woken validation
* Improve reporting of invalid queries
* Akka: configure Coordinated shutdown
* __dev__ Replace ActorLogging by LazyLogging
* __dev__ Register the mainRouter as a distributed pubsub destination actor
* __dev__ Add Metadata query actor, pool it to limit concurrent use
* __dev__ Define algorithm engine to be able to switch engines
* __dev__ Update woken-messages to 2.7.4
* __test__ test many more algorithms
* __test__ Add unit test for metadata queries actor
* __fix__ Fix http connection leak on Chronos client
* __fix__ Stabilise Chronos
* __fix__ Increase size of payloads for messages exchanged between Woken and the portal or validation workers
* __fix__ Metadata queries: support exhaustive argument

## (https://github.com/LREN-CHUV/woken/compare/2.4.0...2.0.0)[2.5.0] - 2018-04-19

* New algorithms
   * hbpmip/python-knn:0.2.3
   * hbpmip/python-sgd-linear-model:0.1.4
   * hbpmip/python-sgd-naive-bayes:0.1.4
   * hbpmip/python-sgd-neural-network:0.1.4
   * hbpmip/python-correlation-heatmap:0.1.2
   * hbpmip/python-gradient-boosting:0.1.4
   * hbpmip/python-jsi-hinmine:0.2.2
   * hbpmip/python-jsi-hedwig:1.0.4
* Update algorithms
   * hbpmip/python-histograms:0.4.3
   * hbpmip/python-linear-regression:0.1.1
   * hbpmip/python-summary-statistics:0.3.0
   * hbpmip/python-tsne:0.4.0
* Update woken-validation to 2.4.6
* API: /metadata/variables - add list of datasets where the variable is present into variables metadata
* SPI: allow overriding algorthims from environment variables, using for example PYTHON_HISTOGRAMS_IMAGE or PYTHON_LINEAR_REGRESSION_IMAGE
* SPI: define for each algorithm whether it supports null values
* SPI: Datasets config: always indicate list of tables
* SPI: Configure db pool size
* Check that the datasets exist in the features database on startup
* Support non predictive algorithms in experiments
* Improve self checks
* __dev__ Update woken-messages to 2.7.1
* __dev__ Update Akka to 2.5.12, Cats to 1.1.0
* __dev__ Add Mining actor and Experiment query actor, pool them to limit concurrent use
* __dev__ Move queries actors to dispatch package
* __test__ Fix integration tests
* __test__ Integration test for summary statistics
* __test__ Update integration tests, experiment with KNN
* __fix__ /metadata/datasets returns NPE
* __fix__ Fix count features
* __fix__ Handle tables without a 'dataset' column

## (https://github.com/LREN-CHUV/woken/compare/2.3.0...2.4.0)[2.4.0] - 2018-03-16

* Update algorithms
   * hbpmip/java-rapidminer-knn:0.2.2
   * hbpmip/java-rapidminer-naivebayes:0.2.1
   * hbpmip/python-histograms:0.3.6
   * hbpmip/python-linear-regression:0.0.7
   * hbpmip/python-summary-statistics:0.1.4
   * hbpmip/python-tsne:0.3.3
   * hbpmip/r-ggparci:0.2.0
* Update Woken validation to 2.3.1
* Queries are automatically filtered by datasets
* API: /metadata/datasets - list available datasets. Uses configuration as the source of information
* API: /metadata/variables - list variables and their metadata
* API: add /health and /readiness checks
* SPI: support mime types for Vega and Vega-lite graphs as results from algorithms
* SPI: support mime types for serialised results (using Python pickle, Java serialization, R RDS) from algorithms
* SPI: mime type for tabular data resource is now application/vnd.dataresource+json
* SPI: provide database configuration settings for Python-based algorithms using SQLAlchemy to connect to the database
* Monitoring: trace Akka HTTP requests (temporarily disabled), Akka remote requests
* Monitoring: host and JVM metrics
* __dev__ Revert to Scala 2.11.12 for serialization compatibility with Woken-validation which requires Scala 2.11
* __dev__ Improve initialisation order of services during startup
* __dev__ Migrate conversion of FilterRules to sql where to woken-messages
* __dev__ Common configuration files for Akka and Kamon
* __dev__ Enable strace for debugging
* __dev__ Update Akka http to 10.1.0-RC2, woken-messages to 2.6.1
* __dev__ Add FeaturesRepository DAO to access features data in the db
* __test__ New cases for experiment flows
* __test__ Improve tests for validation flows
* __test__ Update tests over websockets
* __fix__ Stabilisation of mining and experiment queries
* __doc__ Usage for this application

## (https://github.com/LREN-CHUV/woken/compare/2.2.0...2.3.0)[2.3.0] - 2018-02-20

* Update Woken validation to 2.1.6
* Update algorithms
   * python-anova:0.3.6
   * python-linear-regression:0.0.7
   * python-summary-statistics:0.1.0
   * python-tsne:0.3.3
* Add Akka-based dispatcher and HTTPS to connect to remote Woken instances
* Let's Encrypt support
* Queries can specify target table
* Add label to datasets
* Add Metadata for groups
* Add Kamon monitoring, with support for Zipkin and Prometheus
* __test__ integration tests for distributed functionality
* __test__ use synthetic datasets for testing
* __dev__ Update to Sbt 1.1.0, Scala 2.12.4, woken-messages 2.4.8
* __dev__ Various attempts at finding right serialization and Akka networking configuration
* __dev__ Refactor VariablesMeta, Shapes and document JobResults
* __dev__ Rebrand top level package to ch.chuv.lren.woken

## (https://github.com/LREN-CHUV/woken/compare/2.1.0...2.2.0)[2.2.0] - 2018-01-16

* Distributed datamining
* Distributed experiments
* Add support for Websockets to connect to remote Woken instances
* Clarify and update configuration
* __dev__ Rely on Akka streams for all distributed functionality, also for experiments
* __dev__ Update to Akka 2.5.9

## (https://github.com/LREN-CHUV/woken/compare/2.0.0...2.1.0)[2.1.0] - 2017-12-11

* Requires Chronos 3.0.2 and Zookeeper 3.4.8
* Deep code refactoring, backend by more unit tests
* Monitor jobs on Chronos, track failures and report errors on those jobs
* Move cross-validation module to its own project (woken-validation)
* Extract woken-messages library and make it a dependency
* Add a MasterRouter actor, use Akka to communicate with Portal backend
* Pass variables metatada to algorithms as PARAM_meta variable
* __dev__ Configuration uses Cats Validation
* __dev__ Favor immutable data structures
* __dev__ Update Akka to 2.3.16

## (https://github.com/LREN-CHUV/woken/compare/1.0.0...2.0.0)[2.0.0] - 2016-09-22

* Add support for experiments
* Add cross-validation module

## (https://github.com/LREN-CHUV/woken/compare/c8f18660...1.0.0)[1.0.0] - 2016-03-22

* First stable version

## 0.0.0 - 2015-09-13

* Start project
