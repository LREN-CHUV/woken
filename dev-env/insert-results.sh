#!/bin/sh

sudo docker exec test-postgres \
    /bin/bash -c 'while ! pg_isready -U postgres ; do sleep 1; done && exec psql -U postgres -f /tests/results.sql'
