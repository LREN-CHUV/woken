[![CHUV](https://img.shields.io/badge/CHUV-LREN-AF4C64.svg)](https://www.unil.ch/lren/en/home.html) [![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](https://github.com/LREN-CHUV/woken/blob/master/LICENSE) [![Codacy Badge](https://api.codacy.com/project/badge/Grade/3a1e546e9f124b44a829f2d0ea488a96)](https://www.codacy.com/app/hbp-mip/woken?utm_source=github.com&amp;utm_medium=referral&amp;utm_content=LREN-CHUV/woken&amp;utm_campaign=Badge_Grade) [![Codacy Badge](https://api.codacy.com/project/badge/Coverage/3a1e546e9f124b44a829f2d0ea488a96)](https://www.codacy.com/app/hbp-mip/woken?utm_source=github.com&amp;utm_medium=referral&amp;utm_content=LREN-CHUV/woken&amp;utm_campaign=Badge_Coverage) [![Dependencies](https://app.updateimpact.com/badge/776816605463187456/woken.svg?config=compile)](https://app.updateimpact.com/latest/776816605463187456/woken) [![Build Status](https://travis-ci.org/LREN-CHUV/woken.svg?branch=master)](https://travis-ci.org/LREN-CHUV/woken) [![CircleCI](https://circleci.com/gh/HBPMedical/woken.svg?style=svg)](https://circleci.com/gh/HBPMedical/woken)
<!-- TODO
[![codecov.io](https://codecov.io/github/LREN-CHUV/woken/coverage.svg?branch=master)](https://codecov.io/github/LREN-CHUV/woken?branch=master)
-->


# Woken: Workflow for Analytics

An orchestration platform for Docker containers running data mining algorithms.

This project exposes a web interface to execute on demand data mining algorithms defined in Docker containers and implemented using any tool or language (R, Python, Java and more are supported).

It relies on a runtime environment containing [Mesos](http://mesos.apache.org) and [Chronos])(https://mesos.github.io/chronos/) to control and execute the Docker containers over a cluster.

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

You need the following software installed:

* [Httppie](https://github.com/jakubroztocil/httpie)

```sh
  cd dev-tests
  ./run.sh
```

dev-tests/run.sh uses docker-compose to start a full environment with Mesos, Zookeeper and Chronos, all of those are required for the proper execution of Woken.

5. Create a DNS alias in /etc/hosts

```
  127.0.0.1       localhost frontend

```

6. Browse to [http://frontend:8087](http://frontend:8087/) or run one of the query* script located in folder 'dev-tests'.

## Available Docker containers

The Docker containers that can be executed on this platform require a few specific features.

TODO: define those features - parameters passed as environment variables, in and out directories, entrypoint with a 'compute command', ...

The project [functions-repository](https://github.com/LREN-CHUV/functions-repository) contains the Docker images that can be used with woken.

## Available commands

### Mining query

Performs a data mining task.

TODO: align this API with Exareme

Path: /mining
Verb: POST

Takes a Json document in the body, returns a Json document.

Json input should be of the form:

```json
  {
    "variables": [],
    "covariables": [],
    "grouping": [],
    "filters": [],
    "algorithm": ""
  }
```

where:
* variables is the list of variables
* covariables is the list of covariables
* grouping is the list of variables to group together
* filters is the list of filters
* algorithm is the algorithm to use.

Currently, the following algorithms are supported:
* data: returns the raw data matching the query
* linearRegression: performs a linear regression
* summaryStatistics: performs a summary statistics than can be used to draw box plots.

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
