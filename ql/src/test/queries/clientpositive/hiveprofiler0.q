set hive.exec.operator.hooks=org.apache.hadoop.hive.ql.profiler.HiveProfiler;
set hive.exec.post.hooks=org.apache.hadoop.hive.ql.hooks.HiveProfilerResultsHook;
SET hive.exec.mode.local.auto=false;
SET hive.task.progress=true;

select count(1) from src;
