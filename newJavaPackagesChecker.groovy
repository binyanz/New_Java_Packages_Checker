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
@Field NEWPACKAGE_EMAIL_FLAG = 0 // TODO: might send diffent emails to different people
@Field MISSPROPERTY_EMAIL_FLAG = 0
@Field EMAIL_FLAG = 0
@Field NEW_PACKAGES = 0 //FLAG FOR NEW PACAKAGES
@Field CSVPACKAGE = "Package" //CSV FIRST LINE
@Field CSVOWNER = "Owner"
@Field CSVJIRAID = "JiraID"
@Field CSVPROJECT = "Project"
@Field CSVCOMMENT = "Comment"
node {
    // can only use "" to include ${}
    stage "Checkout"
    //needs to clean up workspace before building
    cleanWs()
    theDir = new File(env.WORKSPACE)

    println theDir.exists() // false (slave node)
    def exists = fileExists 'bocServiceDirectoryRepo'
    if (!exists){
        new File('bocServiceDirectoryRepo').mkdir()
    }
    //checkout bocServiceDirectory Repo to get serviceList.json
    dir('bocServiceDirectoryRepo'){
        git changelog: false, url: urlGithub + 'orca/boc_service_directory.git', credentialsId: credentialIDGithub
    }
    // read serviceList.json file, string->json
    def serviceString = readFile "bocServiceDirectoryRepo/services/serviceList.json"
    def serviceList = new JsonSlurperClassic().parseText(serviceString)

    // checkout each repo and generate latestPackageList.csv
    for(def service in serviceList){
        def serviceRepoName, serviceRepoUrl, serviceMetaUrl
        if (service.keySet().contains( 'id' ) && service.keySet().contains( 'gitRepo' ) && service.keySet().contains( 'metaDataUrl' ) && service.keySet().contains('linkType') ){
            serviceRepoName = service.get('id')
            serviceRepoUrl = service.get('gitRepo')
            serviceMetaUrl = service.get('metaDataUrl')
            serviceLinkType = service.get('linkType')
        } else { // service info not complete, skip
            continue
        }

        pullRepo(serviceRepoName, serviceRepoUrl, serviceLinkType)
        if (serviceRepoName == 'TMS'){ //delete this in production
            writeOriginalOwnershipFile()
        }
        genOwnershipFile(serviceRepoName, serviceMetaUrl)
    }

    stage "Send Email"
    if(EMAIL_FLAG){
        sendEmail()
    }

    stage "Build Artifacts"
    //might need to merge .csv files to one file
    sh'cat output/*_ownershipList.csv > output/packageOwnershipList.csv'
    def csvFirstLine = ([CSVPACKAGE, CSVOWNER, CSVJIRAID, CSVPROJECT, CSVCOMMENT].join(",")) + "\n"
    sh 'sed -i \'1i' + csvFirstLine + '\' output/packageOwnershipList.csv'
    archiveArtifacts artifacts: 'output/*', excludes: 'output/*.md'

    stage "Set Build Status"
    //check if newPackageList.csv exists or not, if exists, make the build UNSTABLE
    if(NEW_PACKAGES){
        //sh 'exit 1'
        currentBuild.result = 'UNSTABLE'
    }
}

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
    sh('find ' + serviceRepoName + '/src/main -type d > ' + serviceRepoName + '/latestPackageList.csv') //TODO: need to check which folder should be updated, under main/src ?
}

def writeOriginalOwnershipFile(){ //this is for test, will delete in production
    //=========================================================================
    // write original_ownership.json file to the folder to test (don't need this in production):
    String jsonString = '''[{\"Package\":\"TMS/src/main\", \"Owner\":\"Binyan\", \"JiraID\":\"FPA43\", \"Project\": \"\", \"Comment\":\"\"},{\"Package\":\"TMS/src/main/resources/com\", \"Owner\":\"Brian Chen\", \"JiraID\":\"FPA43\", \"Project\": \"\", \"Comment\":\"\"}]'''
    writeFile file: 'TMS/original_ownership.json', text: jsonString
    //=========================================================================
}

def genOwnershipFile(serviceRepoName, serviceMetaUrl) {
    //generate list of latest Packages per service
    def latestPackageList = readFile("${serviceRepoName}/latestPackageList.csv").split("\\r?\\n")
    def csvString = ""

    def newPackageList = [:]
    def fileExists = fileExists "${serviceRepoName}/original_ownership.json" //TODO: the file name should be this?
    if (fileExists) {
        echo("original ownership file exist")
        def originalOwnershipString = readFile "${serviceRepoName}/original_ownership.json"
        print originalOwnershipString
        def tempOwnershipDic= new JsonSlurperClassic().parseText(originalOwnershipString) //here the order is different
        print tempOwnershipDic
        // pre-operation for originalOwnershipList:
        def originalOwnershipDic = [:]
        for (def item in tempOwnershipDic) {
            def newKey = item.get(CSVPACKAGE)
            item.remove(CSVPACKAGE)
            originalOwnershipDic[newKey] = item
        }
        // compare with latestPackageList
        for (def tempPackageKey in latestPackageList) {
            if (originalOwnershipDic.containsKey(tempPackageKey)) { //TODO: the value of "Package" might be multiple
                def packageValue = originalOwnershipDic.get(tempPackageKey)
                print packageValue
                // re-order the json obj to write to csv file:
                def tempList = []
                tempList.add(packageValue.get(CSVOWNER))
                tempList.add(packageValue.get(CSVJIRAID))
                tempList.add(packageValue.get(CSVPROJECT))
                tempList.add(packageValue.get(CSVCOMMENT))
                csvString = csvString + (tempList.plus(0, tempPackageKey)).join(",") + "\n"
            } else {// new package detected
                NEW_PACKAGES = 1
                EMAIL_FLAG = 1
                newPackageList[tempPackageKey] = null
            }
        }
        print csvString
    } else { // find default owner in serviceMetadata.json in serviceList.json
        echo("original ownership file doesn't exist")
        EMAIL_FLAG = 1
        def serviceMetaDataString = readFile "bocServiceDirectoryRepo${serviceMetaUrl}"
        def serviceMetaDataList = new JsonSlurperClassic().parseText(serviceMetaDataString)
        def contactList = (serviceMetaDataList.get("contact"))
        def owner = contactList[0].get("name")
        def contactEmail = contactList[0].get("email") // TODO: might use this in the future
        // write owner as the default owner to latestPackageDic: JiraID, Project, Comment are all empty
        def jsonObj = [CSVOWNER: owner, CSVJIRAID:"FPA43", CSVPROJECT: "Orca", CSVCOMMENT: ""]
        print "Value of jsonObj is: ${jsonObj}"
        //generate csv string
        for (def tempPackageKey in latestPackageList) {
            def packageValue = jsonObj
            def tempValueList = packageValue.values() as List
            csvString = csvString + (tempValueList.plus(0,tempPackageKey)).join(",") + "\n"
        }
        print csvString
    }

    sh "mkdir -p output"
    // merge csv files to one:
    writeFile file: "output/${serviceRepoName}_ownershipList.csv", text: csvString
    if (NEW_PACKAGES && !newPackageList.isEmpty){
        def newPackageString = (newPackageList.keySet() as List).join("\n")
        writeFile file: "output/${serviceRepoName}_newPacList.csv", text: newPackageString
    }
}

def sendEmail(){
    mail (  to: 'binyan.zhao@sap.com', // TODO: who will be the receiver? contactEmail or FPA39
            subject: "Job '${env.JOB_NAME}' (${env.BUILD_NUMBER}) detects new JAVA pacakges",
            body: "Please go to ${env.BUILD_URL}, which detects new packages that don't have owner or the original ownership file doesn't exist."
    )

}