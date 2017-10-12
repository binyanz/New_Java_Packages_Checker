import hudson.model.*
import hudson.EnvVars
import hudson.FilePath
import groovy.json.JsonSlurperClassic
import groovy.json.JsonBuilder
import java.nio.file.Files
import java.io.File
import java.lang.Object
import groovy.transform.Field

@Field credentialIDGithub = 'github-fetch-user'
@Field credentialIDGit = '15014aaf-2fd4-4192-83f6-f57b2cb5ed90'
@Field urlGithub = 'git@github.wdf.sap.corp:'
@Field urlGit = 'ssh://fpajenkins@git.wdf.sap.corp:29418/'
@Field NEW_PACKAGES = 0 //FLAG FOR NEW PACAKAGES to upload contact email list
@Field EMAIL_FLAG = 0 //FLAG TO SEND AN EMAIL
//First line of file:
@Field CSVPACKAGE = "Package"
@Field CSVOWNER = "Owner"
@Field CSVJIRAID = "JiraID"
@Field CSVPROJECT = "Project"
@Field CSVCOMMENT = "Comment"
@Field contactEmailList = []
@Field uploadedFile = "uploadedPackageOwnerList.csv"
node {
    /*
    stage: Checkout: checkout out the repo and generate the ownershipList file for each service
    */
    stage "Checkout"
    cleanWs()
    try {
        step($class: 'hudson.plugins.copyartifact.CopyArtifact', projectName: 'New Java Package Checker (TEST)', filter:'output/*_ownershipList.csv', selector: [$class: 'StatusBuildSelector', stable: false])
        echo ' [Info] copy artifacts from the previous build '
    } catch (Exception e) {
        echo ' [Info] No artifacts to copy from previous build '
        //generate output for artifacts:
        sh "mkdir -p output"
    }
    //checkout bocServiceDirectory Repo to get serviceList.json
    def exists = fileExists 'bocServiceDirectoryRepo'
    if (!exists){
        new File('bocServiceDirectoryRepo').mkdir()
    }
    dir('bocServiceDirectoryRepo'){
        git changelog: false, url: urlGithub + 'orca/boc_service_directory.git', credentialsId: credentialIDGithub
    }
    // read serviceList.json file, string->json
    def serviceString = readFile "bocServiceDirectoryRepo/services/serviceList.json"
    def serviceList = new JsonSlurperClassic().parseText(serviceString)
    // checkout each repo and generate latestPackageList.csv
    def REPO_FLAG = 0
    for(def service in serviceList){
        REPO_FLAG = 0
        NEW_PACKAGES = 0
        def serviceRepoName, serviceRepoUrl, serviceMetaUrl
        if (service.keySet().contains( 'id' ) && service.keySet().contains( 'gitRepo' ) && service.keySet().contains( 'metaDataUrl' ) && service.keySet().contains('linkType') ){
            serviceRepoName = service.get('id')
            serviceRepoUrl = service.get('gitRepo')
            serviceMetaUrl = service.get('metaDataUrl')
            serviceLinkType = service.get('linkType')
        } else { // service info not complete, skip
            echo "[Warning]: Info for service ${service} is not sufficient in boc_service_directory, please check the service.json file. "
            continue
        }
        try{
            pullRepo(serviceRepoName, serviceRepoUrl, serviceLinkType)
        }catch (Exception e){
            REPO_FLAG = 1
            echo " [Warning] The repo info for ${serviceRepoName} is not correct ${e}, please check the service.json file. "
        }
        if (!REPO_FLAG){
            genOwnershipFile(serviceRepoName, serviceMetaUrl)
        }
    }

    /*
    stage: Send Email: send the emails to the contact person list. For test email will be only send to Binyan or Harry
     */
    stage "Send Email"
    if(EMAIL_FLAG){
        echo(' [Info] enter into Stage Send Email and will generate the email list ')
        sendEmail()
    }

    /*
    stage: Build Artifacts: archive the files in output/, and generate first line/title for the file
     */
    stage "Build Artifacts"
    def csvFirstLine = ([CSVPACKAGE, CSVOWNER, CSVJIRAID, CSVPROJECT, CSVCOMMENT].join(",")) + "\n"
    sh('ls output > tempListFile.csv')
    def filesList = readFile( "tempListFile.csv" ).split( "\\r?\\n" )
    print filesList
    sh "rm -f tempListFile.csv"
    def i
    for(i=0;i < filesList.size();i++){
        def fileName = 'output/' + filesList[i]
        def tempFileListFirst = (readFile(fileName).split("\\r?\\n"))[0]
        print tempFileListFirst
        if (tempFileListFirst + "\n" == csvFirstLine){
            echo " the title line of ${fileName} is found, skip. "
        }else{
            sh 'sed -i \'1i' + csvFirstLine + '\' ' + fileName
        }
        //TODO:
        //if ( sh('grep -q \'Package,Owner,\' <<< $(head -n 1 outpu(/' + fileName + ')') )
    }
    archiveArtifacts artifacts: 'output/*'

    /*
    stage: Set Build Status: if new packages are detected, the build status will be set to "unstable"
     */
    stage "Set Build Status"
    //check if newPackageList.csv exists or not, make the build UNSTABLE
    if(EMAIL_FLAG){
        currentBuild.result = 'UNSTABLE'
    }
}

// Helper functions:
def pullRepo(serviceRepoName, serviceRepoUrl, serviceLinkType){
    new File(serviceRepoName).mkdir()
    def gitUrlBase, gitCreID
    if(serviceLinkType == "github"){
        gitUrlBase = urlGithub
        gitCreID = credentialIDGithub
    }else {
        gitUrlBase = urlGit
        gitCreID = credentialIDGit
    }
    dir(serviceRepoName){
        git changelog: false, url: gitUrlBase + serviceRepoUrl, credentialsId: gitCreID
    }
    // generate latestPackageList.txt for each service
    sh('find ' + serviceRepoName + '/src/main -type d > ' + serviceRepoName + '/latestPackageList.csv')
}


def genOwnershipFile(serviceRepoName, serviceMetaUrl) {
    // Get file using input step temporarily, the file will be located in build directory. TODO: there is a bug for file parameter in pipeline, will change to file parameter in the future.)
    if (serviceName && serviceName == serviceRepoName){
        def inputFile = input message: 'Upload file here', parameters: [file(name: uploadedFile)]
        echo "[Info] ${inputFile}"
        writeFile(file: uploadedFile, text: inputFile.readToString())
        def uploadFileExists = fileExists uploadedFile
        if (uploadFileExists) {
            echo " [Info] ${uploadedFile} will be used to compare with latestPackageList.csv. "
            sh('mv ' + uploadedFile + ' output/' + serviceName + '_ownershipList.csv')
        }
    }
    def latestPackageList = readFile("${serviceRepoName}/latestPackageList.csv").split("\\r?\\n")
    def contactList = genDefaultContactList(serviceMetaUrl)
    if (contactList.empty){ //skip this service
        echo " [Warning] contactList for ${serviceRepoName} is empty, please check the metadata file. "
        return
    }
    def owner, contactEmail
    try{
        owner = contactList[0].get("name")
        contactEmail = contactList[0].get("email")
    }catch(Exception e){ //skip this service
        echo " [Warning] The default owner or email info is not complete in metadata file: ${e}. "
        return
    }

    def tempValueList = genDefaultOwnerList(owner)
    def ownershipFileExists = fileExists "output/${serviceRepoName}_ownershipList.csv"
    for (def i = 0; i< latestPackageList.size(); i++) { //avoid NoSerialization exception here, use old c loop
        packageItem = latestPackageList[i]
        if(ownershipFileExists){
            echo ' [Info] ownershipList file exists and will go to compareWithFile function. '
            if(compareWithFile(serviceRepoName, packageItem)){
                echo " [Info] ${packageItem} has owner, skip "
            }else{
                echo " [Info] ${packageItem} has no owner, will update with the default owner. "
                addToFile(serviceRepoName, tempValueList, packageItem)
            }
        } else { //initial build or new service with no uploaded ownership file
            echo ' [Info] ownershipList file does not exists and will go to addToFile function. '
            addToFile(serviceRepoName, tempValueList, packageItem)
        }
    }
    if(NEW_PACKAGES){
        contactEmailList.add(contactEmail)
        echo " [Info] contactEmailList is: ${contactEmailList}. "
    }
}

def sendEmail(){
    contactEmailString = contactEmailList.join(',')
    echo " [Info] Contact Email List is ${contactEmailList} "
    echo " [Info] Contact Email List is ${contactEmailString} "
    mail (  to: 'binyan.zhao@sap.com', //should be "${contactEmailString}"
            subject: "Job '${env.JOB_NAME}' (${env.BUILD_NUMBER}) detects new JAVA pacakges",
            body: "Please go to ${env.BUILD_URL}, which detects new packages that don't have owner for your service, with default owner updated. If there is anything you need to change, please go to: xxx wiki page for reference. "
    )
    echo ' [Info] Email is send. '
}

//check if packageItem is included in the ownershipList file
def compareWithFile(serviceRepoName, packageItem ){
    def tempFileString = readFile "output/" + serviceRepoName + '_ownershipList.csv'
    def tempFileList = tempFileString.split("\r?\n")
    for(def fileOwnerValue in tempFileList){
        if((fileOwnerValue.split(','))[0] == packageItem ){
            return true
        }
    }
    return false
}

def addToFile(serviceRepoName, tempValueList, packageItem){
    NEW_PACKAGES = 1
    EMAIL_FLAG = 1
    csvString = (tempValueList.plus(0,packageItem)).join(",")
    sh('echo ' + csvString + ' >> output/' + serviceRepoName + '_ownershipList.csv')
    sh('echo ' + packageItem + ' >> output/' + serviceRepoName + '_newPackageList.csv' )
}

def genDefaultContactList(serviceMetaUrl){
    def contactList = []
    try{
        def serviceMetaDataString = readFile "bocServiceDirectoryRepo${serviceMetaUrl}"
        def serviceMetaDataList = new JsonSlurperClassic().parseText(serviceMetaDataString)
        contactList = (serviceMetaDataList.get("contact"))
    }catch(Exception e){ // skip to continue to do operations on next service
        echo " [Warning] The metadata file for service ${serviceMetaUrl} is unsufficient: ${e}. "
    }
    return contactList
}

def genDefaultOwnerList(owner){
    def jsonObj = [CSVOWNER: owner, CSVJIRAID:"", CSVPROJECT:"", CSVCOMMENT:""]
    def packageValue = jsonObj
    def tempValueList = packageValue.values() as List
    return tempValueList
}