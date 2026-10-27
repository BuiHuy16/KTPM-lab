-- psql imports these from the postgres container environment, without SQL interpolation in a shell.
\getenv user_db_password USER_DB_PASSWORD
\getenv order_db_password ORDER_DB_PASSWORD
CREATE ROLE user_app LOGIN PASSWORD :'user_db_password';
CREATE ROLE order_app LOGIN PASSWORD :'order_db_password';
CREATE DATABASE user_db OWNER user_app;
CREATE DATABASE order_db OWNER order_app;
REVOKE ALL ON DATABASE user_db FROM PUBLIC;
REVOKE ALL ON DATABASE order_db FROM PUBLIC;
GRANT CONNECT ON DATABASE user_db TO user_app;
GRANT CONNECT ON DATABASE order_db TO order_app;
