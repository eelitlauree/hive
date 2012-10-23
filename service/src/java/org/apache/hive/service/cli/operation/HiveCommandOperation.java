/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hive.service.cli.operation;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.hadoop.hive.metastore.api.Schema;
import org.apache.hadoop.hive.ql.processors.CommandProcessor;
import org.apache.hadoop.hive.ql.processors.CommandProcessorResponse;
import org.apache.hadoop.hive.ql.session.SessionState;
import org.apache.hadoop.io.IOUtils;
import org.apache.hive.service.cli.FetchOrientation;
import org.apache.hive.service.cli.HiveSQLException;
import org.apache.hive.service.cli.OperationState;
import org.apache.hive.service.cli.RowSet;
import org.apache.hive.service.cli.TableSchema;
import org.apache.hive.service.cli.session.Session;

/**
 * HiveCommandOperation.
 *
 */
public abstract class HiveCommandOperation extends ExecuteStatementOperation {
  private CommandProcessorResponse response;
  private CommandProcessor commandProcessor;
  private TableSchema resultSchema = null;

  /**
   * For processors other than Hive queries (Driver), they output to session.out (a temp file)
   * first and the fetchOne/fetchN/fetchAll functions get the output from pipeIn.
   */
  private BufferedReader resultReader;


  protected HiveCommandOperation(Session parentSession, String statement, Map<String, String> confOverlay) {
    super(parentSession, statement, confOverlay);
  }

  /* (non-Javadoc)
   * @see org.apache.hive.service.sql.operation.Operation#run()
   */
  @Override
  public void run() throws HiveSQLException {
    setState(OperationState.RUNNING);
    SessionState ss = parentSession.getSessionState();
    PrintStream oldOutStream = ss.out;

    try {
      LOG.info("Putting temp output to file " + ss.getTmpOutputFile());
      ss.out = new PrintStream(new FileOutputStream(ss.getTmpOutputFile()), true, "UTF-8");
      String command = getStatement().trim();
      String[] tokens = statement.split("\\s");
      String commandArgs = command.substring(tokens[0].length()).trim();
      response = getCommandProcessor().run(commandArgs);
      int returnCode = response.getResponseCode();
      String sqlState = response.getSQLState();
      String errorMessage = response.getErrorMessage();
      Schema schema = response.getSchema();
      if (schema != null) {
        setHasResultSet(true);
        resultSchema = new TableSchema(schema);
      } else {
        setHasResultSet(false);
        resultSchema = new TableSchema();
      }
      setState(OperationState.FINISHED);
    } catch (Exception e) {
      setState(OperationState.ERROR);
      throw new HiveSQLException("Error running query: " + e.toString());
    } finally {
      if (ss.out != null) {
        ss.out.close();
      }
      ss.out = oldOutStream;
    }
  }

  /* (non-Javadoc)
   * @see org.apache.hive.service.sql.operation.Operation#close()
   */
  @Override
  public void close() throws HiveSQLException {
    setState(OperationState.CLOSED);
    cleanTmpFile();
  }

  /* (non-Javadoc)
   * @see org.apache.hive.service.sql.operation.Operation#getResultSetSchema()
   */
  @Override
  public TableSchema getResultSetSchema() throws HiveSQLException {
    return resultSchema;
  }

  /* (non-Javadoc)
   * @see org.apache.hive.service.sql.operation.Operation#getNextRowSet(org.apache.hive.service.sql.FetchOrientation, long)
   */
  @Override
  public RowSet getNextRowSet(FetchOrientation orientation, long maxRows) throws HiveSQLException {
    List<String> rows = readResults((int) maxRows);
    RowSet rowSet = new RowSet();

    for (String row : rows) {
      rowSet.addRow(resultSchema, new String[] {row});
    }
    return rowSet;
  }

  /**
   * Reads the temporary results for non-Hive (non-Driver) commands to the
   * resulting List of strings.
   * @param results list of strings containing the results
   * @param nLines number of lines read at once. If it is <= 0, then read all lines.
   */
  private List<String> readResults(int nLines) throws HiveSQLException {
    if (resultReader == null) {
      SessionState sessionState = getParentSession().getSessionState();
      File tmp = sessionState.getTmpOutputFile();
      try {
        resultReader = new BufferedReader(new FileReader(tmp));
      } catch (FileNotFoundException e) {
        LOG.error("File " + tmp + " not found. ", e);
        throw new HiveSQLException(e);
      }
    }

    List<String> results = new ArrayList<String>();

    for (int i = 0; i < nLines || nLines <= 0; ++i) {
      try {
        String line = resultReader.readLine();
        if (line == null) {
          // reached the end of the result file
          break;
        } else {
          results.add(line);
        }
      } catch (IOException e) {
        LOG.error("Reading temp results encountered an exception: ", e);
        throw new HiveSQLException(e);
      }
    }
    return results;
  }

  private void cleanTmpFile() {
    if (resultReader != null) {
      SessionState sessionState = getParentSession().getSessionState();
      File tmp = sessionState.getTmpOutputFile();
      IOUtils.cleanup(LOG, resultReader);
      tmp.delete();
      resultReader = null;
    }
  }

  protected CommandProcessor getCommandProcessor() {
    return commandProcessor;
  }

  protected void setCommandProcessor(CommandProcessor commandProcessor) {
    this.commandProcessor = commandProcessor;
  }
}

