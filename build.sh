#!/bin/bash -e
sbt assembly
cp target/scala-2.11/workflow-assembly-0.1.jar ../mip-docker-images/mip_federation/workflow/downloads/workflow.jar
