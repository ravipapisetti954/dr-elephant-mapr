/*
 * Copyright 2016 LinkedIn Corp.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.linkedin.drelephant.analysis;

import com.linkedin.drelephant.ElephantContext;
import com.linkedin.drelephant.math.Statistics;
import controllers.MetricsController;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.IOException;
import java.lang.Exception;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Queue;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedQueue;
import models.AppResult;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.security.authentication.client.AuthenticatedURL;
import org.apache.hadoop.security.authentication.client.AuthenticationException;
import org.apache.log4j.Logger;
import org.codehaus.jackson.JsonNode;
import org.codehaus.jackson.map.ObjectMapper;


/**
 * This class provides a list of analysis promises to be generated under Hadoop YARN environment
 */
public class AnalyticJobGeneratorHadoop2 implements AnalyticJobGenerator {
  private static final Logger logger = Logger.getLogger(AnalyticJobGeneratorHadoop2.class);
  private static final String RESOURCE_MANAGER_ADDRESS = "yarn.resourcemanager.webapp.address";
  private static final String IS_RM_HA_ENABLED = "yarn.resourcemanager.ha.enabled";
  private static final String RESOURCE_MANAGER_IDS = "yarn.resourcemanager.ha.rm-ids";
  private static final String RM_NODE_STATE_URL = "http://%s/ws/v1/cluster/info";
  private static Configuration configuration;
  private static final String DISTRO_MAPR_FINDER = "hadoop version | grep mapr";
  private static final String DISTRO_MAPR_RESOURCE_MANAGER_ADDRESS = "maprcli urls -name resourcemanager | grep http";
  private static final String DISTRO_MAPR_NAME = "MapR";

  // We provide one minute job fetch delay due to the job sending lag from AM/NM to JobHistoryServer HDFS
    // bml Cardlytics 1-25-2017 1 min (60000ms) isn't enough on smaller clusters; some jobs return null data due to noncompletion of collection. Make it 5 min (300Kms) instead.
  private static final long FETCH_DELAY = 300000;

  // Generate a token update interval with a random deviation so that it does not update the token exactly at the same
  // time with other token updaters (e.g. ElephantFetchers).
  private static final long TOKEN_UPDATE_INTERVAL =
      Statistics.MINUTE_IN_MS * 30 + new Random().nextLong() % (3 * Statistics.MINUTE_IN_MS);

  private String _resourceManagerAddress;
  private long _lastTime = 0;
  private long _currentTime = 0;
  private long _tokenUpdatedTime = 0;
  private AuthenticatedURL.Token _token;
  private AuthenticatedURL _authenticatedURL;
  private final ObjectMapper _objectMapper = new ObjectMapper();

  private final Queue<AnalyticJob> _retryQueue = new ConcurrentLinkedQueue<AnalyticJob>();

  public void updateResourceManagerAddresses() {
        String distroName = null;
        String cmdOut;
        logger.info("Looking for Hadoop distros such as MapR, Cloudera or Hortonworks...");
        try {
            cmdOut = executeShellCommand(DISTRO_MAPR_FINDER, 1);
            if (! cmdOut.isEmpty()) {
                logger.info(DISTRO_MAPR_NAME + " distro detected.");
                distroName = DISTRO_MAPR_NAME;
            }
        } catch (Exception e) { distroName = null; }
        if (distroName == null) {
            logger.info("No specific Hadoop distro given; assuming standard ResourceManager config.");
    if (Boolean.valueOf(configuration.get(IS_RM_HA_ENABLED))) {
      String resourceManagers = configuration.get(RESOURCE_MANAGER_IDS);
      if (resourceManagers != null) {
        logger.info("The list of RM IDs are " + resourceManagers);
        List<String> ids = Arrays.asList(resourceManagers.split(","));
        _currentTime = System.currentTimeMillis();
        updateAuthToken();
        for (String id : ids) {
          try {
            String resourceManager = configuration.get(RESOURCE_MANAGER_ADDRESS + "." + id);
            String resourceManagerURL = String.format(RM_NODE_STATE_URL, resourceManager);
            logger.info("Checking RM URL: " + resourceManagerURL);
            JsonNode rootNode = readJsonNode(new URL(resourceManagerURL));
            String status = rootNode.path("clusterInfo").path("haState").getValueAsText();
            if (status.equals("ACTIVE")) {
              logger.info(resourceManager + " is ACTIVE");
              _resourceManagerAddress = resourceManager;
              break;
            } else {
              logger.info(resourceManager + " is STANDBY");
            }
          } catch (AuthenticationException e) {
            logger.info("Error fetching resource manager " + id + " state " + e.getMessage());
          } catch (IOException e) {
                            logger.info("Error fetching Json for resource manager " + id + " status " + e.getMessage());
          }
        }
      }
    } else {
      _resourceManagerAddress = configuration.get(RESOURCE_MANAGER_ADDRESS);
    }
    if (_resourceManagerAddress == null) {
      throw new RuntimeException(
              "Cannot get YARN resource manager address from Hadoop Configuration property: [" + RESOURCE_MANAGER_ADDRESS
                      + "].");
    }
        } else {
            logger.info(distroName+ " Hadoop distro specified in config; looking for ResourceManager.");
            if (distroName.equals("MapR")) {
                _resourceManagerAddress = executeShellCommand(DISTRO_MAPR_RESOURCE_MANAGER_ADDRESS, 2);
                if (_resourceManagerAddress == null) {
                    throw new RuntimeException(
                            "Cannot get YARN resource manager address from MapR CLI call: [" + DISTRO_MAPR_RESOURCE_MANAGER_ADDRESS
                                    + "].");
                }
                _resourceManagerAddress = _resourceManagerAddress.replace("https://", "").replace("http://", "");
            } else {
                    throw new RuntimeException(
                            "Unknown Hadoop distro specified: [" + distroName + "].");
            }
        }
  }

  @Override
  public void configure(Configuration configuration)
      throws IOException {
    this.configuration = configuration;
    updateResourceManagerAddresses();
  }

  /**
   *  Fetch all the succeeded and failed applications/analytic jobs from the resource manager.
   *
   * @return
   * @throws IOException
   * @throws AuthenticationException
   */
  @Override
  public List<AnalyticJob> fetchAnalyticJobs()
      throws IOException, AuthenticationException {
    List<AnalyticJob> appList = new ArrayList<AnalyticJob>();

    // There is a lag of job data from AM/NM to JobHistoryServer HDFS, we shouldn't use the current time, since there
    // might be new jobs arriving after we fetch jobs. We provide one minute delay to address this lag.
    _currentTime = System.currentTimeMillis() - FETCH_DELAY;
    updateAuthToken();

    logger.info("Fetching recent finished application runs between last time: " + (_lastTime + 1)
        + ", and current time: " + _currentTime);

    // Fetch all succeeded apps
    URL succeededAppsURL = new URL(new URL("http://" + _resourceManagerAddress), String.format(
            "/ws/v1/cluster/apps?finalStatus=SUCCEEDED&finishedTimeBegin=%s&finishedTimeEnd=%s",
            String.valueOf(_lastTime + 1), String.valueOf(_currentTime)));
    logger.info("The succeeded apps URL is " + succeededAppsURL);
    List<AnalyticJob> succeededApps = readApps(succeededAppsURL);
    appList.addAll(succeededApps);

    // Fetch all failed apps
    // state: Application Master State
    // finalStatus: Status of the Application as reported by the Application Master
    URL failedAppsURL = new URL(new URL("http://" + _resourceManagerAddress), String.format(
        "/ws/v1/cluster/apps?finalStatus=FAILED&state=FINISHED&finishedTimeBegin=%s&finishedTimeEnd=%s",
        String.valueOf(_lastTime + 1), String.valueOf(_currentTime)));
    List<AnalyticJob> failedApps = readApps(failedAppsURL);
    logger.info("The failed apps URL is " + failedAppsURL);
    appList.addAll(failedApps);

    // Append promises from the retry queue at the end of the list
    while (!_retryQueue.isEmpty()) {
      appList.add(_retryQueue.poll());
    }

    _lastTime = _currentTime;
    return appList;
  }

  @Override
  public void addIntoRetries(AnalyticJob promise) {
    _retryQueue.add(promise);
    int retryQueueSize = _retryQueue.size();
    MetricsController.setRetryQueueSize(retryQueueSize);
    logger.info("Retry queue size is " + retryQueueSize);
  }

  /**
   * Authenticate and update the token
   */
  private void updateAuthToken() {
    if (_currentTime - _tokenUpdatedTime > TOKEN_UPDATE_INTERVAL) {
      logger.info("AnalysisProvider updating its Authenticate Token...");
      _token = new AuthenticatedURL.Token();
      _authenticatedURL = new AuthenticatedURL();
      _tokenUpdatedTime = _currentTime;
    }
  }

  /**
   * Connect to url using token and return the JsonNode
   *
   * @param url The url to connect to
   * @return
   * @throws IOException Unable to get the stream
   * @throws AuthenticationException Authencation problem
   */
  private JsonNode readJsonNode(URL url)
      throws IOException, AuthenticationException {
    HttpURLConnection conn = _authenticatedURL.openConnection(url, _token);
    return _objectMapper.readTree(conn.getInputStream());
  }

  /**
   * Parse the returned json from Resource manager
   *
   * @param url The REST call
   * @return
   * @throws IOException
   * @throws AuthenticationException Problem authenticating to resource manager
   */
  private List<AnalyticJob> readApps(URL url) throws IOException, AuthenticationException{
    List<AnalyticJob> appList = new ArrayList<AnalyticJob>();

    JsonNode rootNode = readJsonNode(url);
    JsonNode apps = rootNode.path("apps").path("app");

    for (JsonNode app : apps) {
      String appId = app.get("id").getValueAsText();

      // When called first time after launch, hit the DB and avoid duplicated analytic jobs that have been analyzed
      // before.
      if (_lastTime > 0 || (_lastTime == 0 && AppResult.find.byId(appId) == null)) {
        String user = app.get("user").getValueAsText();
        String name = app.get("name").getValueAsText();
        String queueName = app.get("queue").getValueAsText();
        String trackingUrl = app.get("trackingUrl") != null? app.get("trackingUrl").getValueAsText() : null;
        long startTime = app.get("startedTime").getLongValue();
        long finishTime = app.get("finishedTime").getLongValue();

        ApplicationType type =
            ElephantContext.instance().getApplicationTypeForName(app.get("applicationType").getValueAsText());

        // If the application type is supported
        if (type != null) {
          AnalyticJob analyticJob = new AnalyticJob();
          analyticJob.setAppId(appId).setAppType(type).setUser(user).setName(name).setQueueName(queueName)
              .setTrackingUrl(trackingUrl).setStartTime(startTime).setFinishTime(finishTime);

          appList.add(analyticJob);
        }
      }
    }
    return appList;
  }

    private String executeShellCommand(String command, long linenumber) {

        if (linenumber < 0) {
            return null;
        }
        StringBuffer output = new StringBuffer();

        Process p;
        try {
            p = Runtime.getRuntime().exec(command);
            p.waitFor();
            BufferedReader reader =
                    new BufferedReader(new InputStreamReader(p.getInputStream()));

            String line = "";
            if (linenumber == 0) {
                while ((line = reader.readLine()) != null) {
                    output.append(line + "\n");
                }
            } else {
                long i = 1;
                while ((line = reader.readLine()) != null && i <= linenumber) {
                    if (i == linenumber) {
                        output.append(line);
                        break;
                    }
                    i = i + 1;
                }

            }

        } catch (Exception e) {
            logger.error(e.getMessage());
        }

        return output.toString();

    }
}
