
CREATE TABLE job_result
(
  request_id character varying(32) NOT NULL,
  node character varying(32) NOT NULL,
  timestamp timestamp,
  data text,
  error character varying(256),

  CONSTRAINT pk_job_result PRIMARY KEY (request_id, node)
)
WITH (
  OIDS=FALSE
);
