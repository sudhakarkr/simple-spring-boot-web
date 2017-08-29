#!/usr/bin/groovy

////
// This pipeline requires the following plugins:
// Kubernetes Plugin 0.10
////

String ocpApiServer = env.OCP_API_SERVER ? "${env.OCP_API_SERVER}" : "https://openshift.default.svc.cluster.local"

node('master') {
  
  env.NAMESPACE = readFile('/var/run/secrets/kubernetes.io/serviceaccount/namespace').trim()
  env.TOKEN = readFile('/var/run/secrets/kubernetes.io/serviceaccount/token').trim()
  env.OC_CMD = "oc --request-timeout='0' --token=${env.TOKEN} --server=${ocpApiServer} --certificate-authority=/run/secrets/kubernetes.io/serviceaccount/ca.crt --namespace=${env.NAMESPACE}"

  env.APP_NAME = "${env.JOB_NAME}".replaceAll(/-?pipeline-?/, '').replaceAll(/-?${env.NAMESPACE}-?/, '')
  def projectBase = "${env.NAMESPACE}".replaceAll(/-dev/, '')
  env.STAGE1 = "${projectBase}-dev"
  env.STAGE2 = "${projectBase}-stage"
  env.STAGE3 = "${projectBase}-prod"

//  sh(returnStdout: true, script: "${env.OC_CMD} get is jenkins-slave-image-mgmt --template=\'{{ .status.dockerImageRepository }}\' -n openshift > /tmp/jenkins-slave-image-mgmt.out")
//  env.SKOPEO_SLAVE_IMAGE = readFile('/tmp/jenkins-slave-image-mgmt.out').trim()
   println "${ocpApiServer}"
   //sh "env"
}

node('maven') {
//  def artifactory = Artifactory.server(env.ARTIFACTORY_SERVER)
  // def artifactoryMaven = Artifactory.newMavenBuild()
  // def buildInfo = Artifactory.newBuildInfo()
  // def scannerHome = tool env.SONARQUBE_TOOL
  def mvnHome = env.MAVEN_HOME ? "${env.MAVEN_HOME}" : "/usr/share/maven/"
  def mvnCmd = "mvn"
  String pomFileLocation = env.BUILD_CONTEXT_DIR ? "${env.BUILD_CONTEXT_DIR}/pom.xml" : "pom.xml"

  // The following variables need to be defined at the top level and not inside
  // the scope of a stage - otherwise they would not be accessible from other stages.
  // Extract version and other properties from the pom.xml
  def groupId    = getGroupIdFromPom("/pom.xml")
  def artifactId = getArtifactIdFromPom("/pom.xml")
  def version    = getVersionFromPom("/pom.xml")


  stage('SCM Checkout') {

    println("Current version:" + version)
    println("Artifact ID:" + artifactId + ", Group ID:" + groupId)

    checkout scm
    sh "orig=\$(pwd); cd \$(dirname ${pomFileLocation}); git describe --tags; cd \$orig"
  }

  stage('Build') {

    sh "${mvnCmd} clean install -DskipTests=true -f ${pomFileLocation}"

  }

  stage('unit test') {

    sh "${mvnCmd} test -f ${pomFileLocation}"

  }

  stage('Build Image') {

    sh """
       rm -rf oc-build && mkdir -p oc-build/deployments

       for t in \$(echo "jar;war;ear" | tr ";" "\\n"); do
         cp -rfv ./target/*.\$t oc-build/deployments/ 2> /dev/null || echo "No \$t files"
       done

       ${env.OC_CMD} start-build ${env.APP_NAME} --from-dir=oc-build --wait=true --follow=true || exit 1
     """
  }

  stage("Verify Deployment to ${env.STAGE1}") {

    openshiftVerifyDeployment(deploymentConfig: "${env.APP_NAME}", namespace: "${STAGE1}", verifyReplicaCount: true)

    //input "Promote Application to Stage?"
  }

  stage("Promote To ${env.STAGE2}") {
    openshiftTag (alias: 'true', apiURL: "${ocpApiServer}", 
                  authToken: "${env.TOKEN}", destStream: "${env.APP_NAME}", 
                  destTag: "${version}", destinationAuthToken: "${env.TOKEN}", destinationNamespace: "${env.STAGE2}", 
                  namespace: "${env.STAGE1}", srcStream: "${env.APP_NAME}", srcTag: 'latest', verbose: 'false')
  }

  stage("Verify Deployment to ${env.STAGE2}") {

    openshiftVerifyDeployment(deploymentConfig: "${env.APP_NAME}", namespace: "${STAGE2}", verifyReplicaCount: true)

    input "Promote Application to ${env.STAGE3}?"
  }

  def tag = "blue"
  def altTag = "green"
  
  stage("Promote To ${env.STAGE3}") {
  
    sh "oc get route "${env.APP_NAME}" -n ${env.STAGE3} -o jsonpath='{ .spec.to.name }' --loglevel=4 > activeservice"
    activeService = readFile('activeservice').trim()
    println("Current active service:" + activeService)
    if (activeService == "${env.APP_NAME}-blue") {
       tag = "green"
       altTag = "blue"
    }
  
    openshiftTag (alias: 'true', apiURL: "${ocpApiServer}", 
                  authToken: "${env.TOKEN}", destStream: "${env.APP_NAME}-${tag}", 
                  destTag: "${version}", destinationAuthToken: "${env.TOKEN}", destinationNamespace: "${env.STAGE3}", 
                  namespace: "${env.STAGE2}", srcStream: "${env.APP_NAME}", srcTag: "${version}", verbose: 'false')

    // Switch Route to new active c
    sh "oc patch route ${env.APP_NAME} --patch '{\"spec\": { \"to\": { \"name\": \"${env.APP_NAME}-${tag}\"}}}' -n ${env.STAGE3}"
    println("Route switched to: " + tag)
  }

  stage("Verify Deployment to ${env.STAGE3}") {

    openshiftVerifyDeployment(deploymentConfig: "${env.APP_NAME}-${tag}", namespace: "${STAGE3}", verifyReplicaCount: true)
    println "Application ${env.APP_NAME}-${tag} is now in Production!"

  }
}


// Convenience Functions to read variables from the pom.xml
// Do not change anything below this line.
def getVersionFromPom(pom) {
  def matcher = readFile(pom) =~ '<version>(.+)</version>'
  matcher ? matcher[0][1] : null
}
def getGroupIdFromPom(pom) {
  def matcher = readFile(pom) =~ '<groupId>(.+)</groupId>'
  matcher ? matcher[0][1] : null
}
def getArtifactIdFromPom(pom) {
  def matcher = readFile(pom) =~ '<artifactId>(.+)</artifactId>'
  matcher ? matcher[0][1] : null
}
