package de.gesellix.docker.client

import groovy.json.JsonBuilder
import groovy.json.JsonSlurper
import groovyx.net.http.ContentType
import groovyx.net.http.HttpResponseDecorator
import groovyx.net.http.RESTClient
import org.apache.commons.io.IOUtils
import org.codehaus.groovy.runtime.MethodClosure
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class DockerClientImpl implements DockerClient {

  private static Logger logger = LoggerFactory.getLogger(DockerClientImpl)

  def responseHandler = new ChunkedResponseHandler()

  def dockerHost = "http://127.0.0.1:2375/"
  def delegate

  def getDelegate() {
    if (!delegate) {
      this.delegate = new RESTClient(dockerHost)
      this.delegate.handler.failure = { response ->
        logger.error "Failure: $response.statusLine"
      }
      logger.info "using docker at '${dockerHost}'"
    }
    return delegate
  }

  @Override
  def auth(def authDetails) {
    logger.info "auth..."
    getDelegate().handler.'200' = null
    getDelegate().post([path              : "/auth",
                        body              : authDetails,
                        requestContentType: ContentType.JSON
    ]) { response ->
      logger.info "${response.statusLine}"
      return response.statusLine.statusCode
    }
  }

  @Override
  def encodeAuthConfig(def authConfig) {
    logger.info "encodeAuthConfig..."
    return new JsonBuilder(authConfig).toString().bytes.encodeBase64().toString()
  }

  @Override
  def build(InputStream buildContext, removeIntermediateContainers = true) {
    logger.info "build image..."
    getDelegate().handler.'200' = new MethodClosure(responseHandler, "handleResponse")
    getDelegate().post([path              : "/build",
                        query             : ["rm": removeIntermediateContainers],
                        body              : IOUtils.toByteArray(buildContext),
                        requestContentType: ContentType.BINARY])

    def lastResponseDetail = responseHandler.lastResponseDetail
    logger.info "${lastResponseDetail}"
    return lastResponseDetail.stream.trim() - "Successfully built "
  }

  @Override
  def tag(imageId, name) {
    logger.info "tag image"
//    getDelegate().handler.'200' = null
    getDelegate().post([path : "/images/${imageId}/tag".toString(),
                        query: [repo : name,
                                force: 0]]) { response ->
      logger.info "${response.statusLine}"
      return response.statusLine.statusCode
    }
  }

  @Override
  def push(imageName, authBase64Encoded = ".", registry = "") {
    logger.info "push image '${imageName}'"

    def actualImageName = imageName
    if (registry) {
      actualImageName = "$registry/$imageName".toString()
      tag(imageName, actualImageName)
    }
    getDelegate().handler.'200' = new MethodClosure(responseHandler, "handleResponse")
    getDelegate().post([path   : "/images/${actualImageName}/push".toString(),
                        query  : ["registry": registry],
                        headers: ["X-Registry-Auth": authBase64Encoded ?: "."]])

    def lastResponseDetail = responseHandler.lastResponseDetail
    logger.info "${lastResponseDetail}"
    return lastResponseDetail
  }

  @Override
  def pull(imageName, tag = "", registry = "") {
    logger.info "pull image '${imageName}'..."

    def actualImageName = imageName
    if (registry) {
      actualImageName = "$registry/$imageName".toString()
    }

    getDelegate().handler.'200' = new MethodClosure(responseHandler, "handleResponse")
    getDelegate().post([path : "/images/create",
                        query: [fromImage: actualImageName,
                                tag      : tag,
                                registry : registry]])

    def lastResponseDetail = responseHandler.lastResponseDetail
    logger.info "${lastResponseDetail}"
    return lastResponseDetail.id
  }

  @Override
  def createContainer(containerConfig, name = "") {
    logger.info "create container..."
    def defaultContainerConfig = ["Hostname"      : "",
                                  "User"          : "",
                                  "Memory"        : 0,
                                  "MemorySwap"    : 0,
                                  "AttachStdin"   : false,
                                  "AttachStdout"  : true,
                                  "AttachStderr"  : true,
                                  "PortSpecs"     : null,
                                  "Tty"           : false,
                                  "OpenStdin"     : false,
                                  "StdinOnce"     : false,
                                  "Env"           : null,
                                  "Cmd"           : [],
                                  "Image"         : null,
                                  "Volumes"       : [],
                                  "WorkingDir"    : "",
                                  "DisableNetwork": false,
                                  "ExposedPorts"  : [
                                  ]]

//    defaultContainerConfig = [:]
    def actualContainerConfig = defaultContainerConfig + containerConfig

//    getDelegate().handler.'200' = null
    getDelegate().post([path              : "/containers/create".toString(),
                        query             : ["name": name],
                        body              : actualContainerConfig,
                        requestContentType: ContentType.JSON]) { response, reader ->
      logger.info "${response.statusLine}"
      return reader
    }
  }

  @Override
  def startContainer(containerId, hostConfig = [:]) {
    logger.info "start container..."
    def defaultHostConfig = ["Binds"          : [],
                             "Links"          : [],
                             "LxcConf"        : [],
                             "PortBindings"   : [],
                             "PublishAllPorts": false,
                             "Privileged"     : false,
                             "Dns"            : [],
                             "VolumesFrom"    : [
                             ]]

    defaultHostConfig = [:]
    def actualHostConfig = defaultHostConfig + hostConfig

//    getDelegate().handler.'200' = null
    getDelegate().post([path              : "/containers/${containerId}/start".toString(),
                        body              : actualHostConfig,
                        requestContentType: ContentType.JSON]) { response, reader ->
      logger.info "${response.statusLine}"
      return response.statusLine.statusCode
    }
  }

  @Override
  def run(fromImage, containerConfig, hostConfig, tag = "", name = "") {
    logger.info "run container"
/*
    http://docs.docker.com/reference/api/docker_remote_api_v1.13/#31-inside-docker-run

    Here are the steps of ‘docker run’ :
      Create the container
      If the status code is 404, it means the image doesn’t exist:
        - Try to pull it
        - Then retry to create the container
      Start the container
      If you are not in detached mode:
        - Attach to the container, using logs=1 (to have stdout and stderr from the container’s start) and stream=1
      If in detached mode or only stdin is attached:
        - Display the container’s id
*/
    def containerConfigWithImageName = [:] + containerConfig
    containerConfigWithImageName.Image = fromImage + (tag ? ":$tag" : "")

    pull(fromImage, tag)

    def containerInfo = createContainer(containerConfigWithImageName, name)
    def result = startContainer(containerInfo.Id, hostConfig)
    return [
        container: containerInfo,
        status   : result
    ]
  }

  @Override
  def stop(containerId) {
    logger.info "stop container"
    getDelegate().handler.'200' = null
    getDelegate().post([path: "/containers/${containerId}/stop".toString()]) { response ->
      logger.info "${response.statusLine}"
      return response.statusLine.statusCode
    }
  }

  @Override
  def wait(containerId) {
    logger.info "wait container"
    getDelegate().handler.'200' = null
    getDelegate().post([path: "/containers/${containerId}/wait".toString()]) { response, reader ->
      logger.info "${response.statusLine}"
      return [status  : response.statusLine,
              response: reader]
    }
  }

  @Override
  def rm(containerId) {
    logger.info "rm container"
    getDelegate().handler.'200' = null
    def response = getDelegate().delete([path: "/containers/${containerId}".toString()])
    logger.info "${response.statusLine}"
    return response.statusLine.statusCode
  }

  @Override
  def rmi() {
    logger.info "rm image"
  }

  @Override
  def ps() {
    logger.info "list containers"
    getDelegate().handler.'200' = new MethodClosure(responseHandler, "handleResponse")
    getDelegate().get([path : "/containers/json",
                       query: [all : true,
                               size: true]])

    def completeResponse = responseHandler.completeResponse
    def containersAsJson = new JsonSlurper().parseText(completeResponse)
    logger.info "${containersAsJson}"
    return containersAsJson
  }

  @Override
  def inspectContainer(containerId) {
    logger.info "inspect container"
    getDelegate().handler.'200' = new MethodClosure(responseHandler, "handleResponse")
    getDelegate().get([path: "/containers/${containerId}/json"])

    def completeResponse = responseHandler.completeResponse
    def resultAsJson = new JsonSlurper().parseText(completeResponse)
    logger.info "${resultAsJson}"
    return resultAsJson
  }

  @Override
  def images() {
    logger.info "list images"
    getDelegate().handler.'200' = null
    getDelegate().get([path : "/images/json",
                       query: [all: 0]]) { response, reader ->
      logger.info "${response.statusLine}"
      return reader
    }
  }

  static class ChunkedResponseHandler {

    def completeResponse

    def handleResponse(HttpResponseDecorator response) {
      logger.info "response: $response.statusLine"
      completeResponse = ""
      new InputStreamReader(response.entity.content).each { chunk ->
        logger.debug("received chunk: '${chunk}'")
        completeResponse += chunk
      }
    }

    def getLastResponseDetail() {
      if (completeResponse) {
        logger.debug("find last detail in: '${completeResponse}'")
        def lastResponseDetail = completeResponse.substring(completeResponse.lastIndexOf("}{") + 1)
        return new JsonSlurper().parseText(lastResponseDetail)
      }
      else {
        return ""
      }
    }
  }
}
