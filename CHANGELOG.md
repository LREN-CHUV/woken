
# Changelog

## 2.1.1 - 2017-12-14

* Clarify and update configuration

## 2.1.0 - 2017-12-11

* Requires Chronos 3.0.2 and Zookeeper 3.4.8
* Deep code refactoring, backend by more unit tests
* Monitor jobs on Chronos, track failures and report errors on those jobs
* [dev] Configuration uses Cats Validation
* [dev] Favor immutable data structures

## 2.0.2 - 2017-10-23

* Update Akka to 2.3.16

## 2.0.1 - 2017-10-13

* Move cross-validation module to its own project (woken-validation)
* Extract woken-messages library and make it a dependency
* Add a MasterRouter actor, use Akka to communicate with Portal backend
* Pass variables metatada to algorithms as PARAM_meta variable

## 2.0.0 - 2016-09-22

* Add support for experiments
* Add cross-validation module

## 1.0.0 - 2016-03-22

* First stable version
