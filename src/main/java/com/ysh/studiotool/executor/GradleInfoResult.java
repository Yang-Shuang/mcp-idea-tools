package com.ysh.studiotool.executor;

import java.util.HashMap;
import java.util.List;
import java.util.TreeSet;

public class GradleInfoResult extends BaseResult {

    private String projectName;
    private String projectInfo;
    private String distributionType;
    private String gradleJvm;
    private String gradleHome;
    private String gradleUserHome;
    private String selectedRunConfig;
    private List<String> runConfigs;
    private HashMap<String, TreeSet<String>> tasks;

    public GradleInfoResult(String success, String message, String error) {
        super(success, message, error);
    }

    public String getProjectName() {
        return projectName;
    }

    public void setProjectName(String projectName) {
        this.projectName = projectName;
    }

    public String getProjectInfo() {
        return projectInfo;
    }

    public void setProjectInfo(String projectInfo) {
        this.projectInfo = projectInfo;
    }

    public String getDistributionType() {
        return distributionType;
    }

    public void setDistributionType(String distributionType) {
        this.distributionType = distributionType;
    }

    public String getGradleJvm() {
        return gradleJvm;
    }

    public void setGradleJvm(String gradleJvm) {
        this.gradleJvm = gradleJvm;
    }

    public String getGradleHome() {
        return gradleHome;
    }

    public void setGradleHome(String gradleHome) {
        this.gradleHome = gradleHome;
    }

    public String getGradleUserHome() {
        return gradleUserHome;
    }

    public void setGradleUserHome(String gradleUserHome) {
        this.gradleUserHome = gradleUserHome;
    }

    public String getSelectedRunConfig() {
        return selectedRunConfig;
    }

    public void setSelectedRunConfig(String selectedRunConfig) {
        this.selectedRunConfig = selectedRunConfig;
    }

    public List<String> getRunConfigs() {
        return runConfigs;
    }

    public void setRunConfigs(List<String> runConfigs) {
        this.runConfigs = runConfigs;
    }

    public HashMap<String, TreeSet<String>> getTasks() {
        return tasks;
    }

    public void setTasks(HashMap<String, TreeSet<String>> tasks) {
        this.tasks = tasks;
    }

}
