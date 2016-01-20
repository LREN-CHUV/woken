#!/bin/sh
docker run -d \
  -v $(pwd)/conf:/etc/mip/
  --net=bridge \
  --dns 155.105.251.102 --dns 155.105.251.86 \
  registry.federation.mip.hbp/mip_federation/workflow
