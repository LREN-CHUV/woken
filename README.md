[![CHUV](https://img.shields.io/badge/CHUV-LREN-AF4C64.svg)](https://www.unil.ch/lren/en/home.html) [![License](https://img.shields.io/badge/license-AGPL--3.0-blue.svg)](https://github.com/LREN-CHUV/woken/blob/master/LICENSE) [![Codacy Badge](https://api.codacy.com/project/badge/Grade/3a1e546e9f124b44a829f2d0ea488a96)](https://www.codacy.com/app/hbp-mip/woken?utm_source=github.com&amp;utm_medium=referral&amp;utm_content=LREN-CHUV/woken&amp;utm_campaign=Badge_Grade) [![Codacy Badge](https://api.codacy.com/project/badge/Coverage/3a1e546e9f124b44a829f2d0ea488a96)](https://www.codacy.com/app/hbp-mip/woken?utm_source=github.com&amp;utm_medium=referral&amp;utm_content=LREN-CHUV/woken&amp;utm_campaign=Badge_Coverage) [![Dependencies](https://app.updateimpact.com/badge/776816605463187456/woken.svg?config=compile)](https://app.updateimpact.com/latest/776816605463187456/woken) [![Build Status](https://travis-ci.org/LREN-CHUV/woken.svg?branch=master)](https://travis-ci.org/LREN-CHUV/woken) [![CircleCI](https://circleci.com/gh/HBPMedical/woken.svg?style=svg)](https://circleci.com/gh/HBPMedical/woken)
<!-- TODO
[![codecov.io](https://codecov.io/github/LREN-CHUV/woken/coverage.svg?branch=master)](https://codecov.io/github/LREN-CHUV/woken?branch=master)
-->


# Woken: Workflow for Analytics

An orchestration platform for Docker containers running data mining algorithms.

This project exposes a web interface to execute on demand data mining algorithms defined in Docker containers and implemented using any tool or language (R, Python, Java and more are supported).

It relies on a runtime environment containing [Mesos](http://mesos.apache.org) and [Chronos])(https://mesos.github.io/chronos/) to control and execute the Docker containers over a cluster.

## Usage

```sh

 docker run --rm --env [list of environment variables] --link woken hbpmip/woken:2.5.4

```

where the environment variables are:

* CLUSTER_IP: Name of this server advertised in the Akka cluster
* CLUSTER_PORT: Port of this server advertised in the Akka cluster
* CLUSTER_NAME: Name of Woken cluster, default to 'woken'
* WOKEN_PORT_8088_TCP_ADDR: Address of Woken master server
* WOKEN_PORT_8088_TCP_PORT: Port of Woken master server, default to 8088
* DOCKER_BRIDGE_NETWORK: Name of the Docker bridge network. Default to 'bridge'
* NETWORK_INTERFACE: IP address for listening to incoming HTTP connections. Default to '0.0.0.0'
* WEB_SERVICES_PORT: Port for the HTTP server in Docker container. Default to 8087
* WEB_SERVICES_SECURE: If yes, HTTPS with a custom certificate will be used. Default to no.
* WEB_SERVICES_USER: Name used to protected the web servers protected with HTTP basic authentication. Default to 'admin'
* WEB_SERVICES_PASSWORD: Password used to protected the web servers protected with HTTP basic authentication.
* LOG_LEVEL: Level for logs on standard output, default to WARNING
* LOG_CONFIG: on/off - log configuration on start, default to off
* VALIDATION_MIN_SERVERS: minimum number of servers with the 'validation' functionality in the cluster, default to 0
* SCORING_MIN_SERVERS: minimum number of servers with the 'scoring' functionality in the cluster, default to 0
* KAMON_ENABLED: enable monitoring with Kamon, default to no
* ZIPKIN_ENABLED: enable reporting traces to Zipkin, default to no. Requires Kamon enabled.
* ZIPKIN_IP: IP address to Zipkin server. Requires Kamon and Zipkin enabled.
* ZIPKIN_PORT: Port to Zipkin server. Requires Kamon and Zipkin enabled.
* PROMETHEUS_ENABLED: enable reporting metrics to Prometheus, default to no. Requires Kamon enabled.
* PROMETHEUS_IP: IP address to Prometheus server. Requires Kamon and Prometheus enabled.
* PROMETHEUS_PORT: Port to Prometheus server. Requires Kamon and Prometheus enabled.
* SIGAR_SYSTEM_METRICS: Enable collection of metrics of the system using Sigar native library, default to no. Requires Kamon enabled.
* JVM_SYSTEM_METRICS: Enable collection of metrics of the JVM using JMX, default to no. Requires Kamon enabled.
* MINING_LIMIT: Maximum number of concurrent mining operations. Default to 100
* EXPERIMENT_LIMIT: Maximum number of concurrent experiments. Default to 100

## Getting started

Follow these steps to get started:

1. Git-clone this repository.

```sh
  git clone https://github.com/LREN-CHUV/woken.git
```

2. Change directory into your clone:

```sh
  cd woken
```

3. Build the application

You need the following software installed:

* [Docker](https://www.docker.com/) 17.06 or better with docker-compose
* [Captain](https://github.com/harbur/captain) 1.1.0 or better

```sh
  ./build.sh
```

4. Run the application

You need the following software installed to execute some tests:

* [Httppie](https://github.com/jakubroztocil/httpie)

```sh
  cd tests
  ./run.sh
```

tests/run.sh uses docker-compose to start a full environment with Mesos, Zookeeper and Chronos, all of those are required for the proper execution of Woken.

5. Create a DNS alias in /etc/hosts

```
  127.0.0.1       localhost frontend

```

6. Browse to [http://frontend:8087](http://frontend:8087/) or run one of the query* script located in folder 'tests'.

## Available Docker containers

The Docker containers that can be executed on this platform require a few specific features.

TODO: define those features - parameters passed as environment variables, in and out directories, entrypoint with a 'compute command', ...

The project [algorithm-repository](https://github.com/LREN-CHUV/algorithm-repository) contains the Docker images that can be used with woken.

## Available commands

### Mining query

Performs a data mining task.

Path: /mining/job
Verb: POST

Takes a Json document in the body, returns a Json document.

Json input should be of the form:

```json
  {
    "user": {"code": "user1"},
    "variables": [{"code": "var1"}],
    "covariables": [{"code": "var2"},{"code": "var3"}],
    "grouping": [{"code": "var4"}],
    "filters": [],
    "algorithm": "",
    "datasets": [{"code": "dataset1"},{"code": "dataset2"}]
  }
```

where:
* variables is the list of variables
* covariables is the list of covariables
* grouping is the list of variables to group together
* filters is the list of filters. The format used here is coming from [JQuery QueryBuilder filters](http://querybuilder.js.org/#filters), for example ```{"condition":"AND","rules":[{"id":"FULLNAME", "field":"FULLNAME","type":"string","input":"text","operator":"equal","value":"Isaac Fulmer"}],"valid":true}```
* datasets is an optional list of datasets, it can be used in distributed mode to select the nodes to query and in all cases add a filter rule of type ```{"condition":"OR","rules":[{"field":"dataset","operator","equals","value":"dataset1"},{"field":"dataset","operator","equals","value":"dataset2"}]}```
* algorithm is the algorithm to use.

Currently, the following algorithms are supported:
* data: returns the raw data matching the query
* linearRegression: performs a linear regression
* summaryStatistics: performs a summary statistics than can be used to draw box plots.
* knn
* naiveBayes

### Experiment query

Performs an experiment comprised of several data mining tasks and an optional cross-validation step used to compute the fitness of each algorithm and select the best result.

TODO: document API

## Release

You need the following software installed:

* [Bumpversion](https://github.com/peritus/bumpversion)
* [Precommit](http://pre-commit.com/)

Execute the following commands to distribute Woken as a Docker container:

```sh
  ./publish.sh
```

## Installation

For production, woken requires Mesos and Chronos. To install them, you can use either:

* [mip-microservices-infrastructure](https://github.com/LREN-CHUV/mip-microservices-infrastructure), a collection of Ansible scripts deploying a full Mesos stack on Ubuntu servers.
* [mantl.io](https://github.com/CiscoCloud/mantl), a microservice infrstructure by Cisco, based on Mesos.
* [Mesosphere DCOS](https://dcos.io/) DC/OS (the datacenter operating system) is an open-source, distributed operating system based on the Apache Mesos distributed systems kernel.

# What's in a name?

Woken :

* the Woken river in China - we were looking for rivers in China
* passive form of awake - it launches Docker containers and computations
* workflow - the previous name, not too different

# Acknowledgements

This work has been funded by the European Union Seventh Framework Program (FP7/2007­2013) under grant agreement no. 604102 (HBP)

This work is part of SP8 of the Human Brain Project (SGA1).
