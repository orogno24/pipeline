def runFtcPipeline(Map config = [:]) {
    def appName = config.appName ?: ''
    def triggerToken = config.triggerToken ?: ''
    def gitBranchName = config.gitBranchName ?: 'main'
    def giteaOwner = config.giteaOwner ?: 'it-department'
    def redmineApiBase = config.redmineApiBase ?: 'http://redmine.cicd.svc.cluster.local'
    def redmineUrl = config.redmineUrl ?: 'http://redmine.cicd.svc.cluster.local'

    if (!appName?.trim()) {
        error('config.appName is required.')
    }
    if (!triggerToken?.trim()) {
        error('config.triggerToken is required.')
    }

    pipeline {
        agent {
            kubernetes {
                label 'dind-ftc'
            }
        }

        triggers {
            GenericTrigger(
                genericVariables: [
                    [key: 'ref', value: '$.ref'],
                    [key: 'after', value: '$.after'],
                    [key: 'COMMIT_MESSAGE', value: '$.commits[0].message']
                ],
                token: triggerToken,
                regexpFilterText: '$ref',
                regexpFilterExpression: "refs/heads/${gitBranchName}"
            )
        }

        environment {
            APP_NAME = "${appName}"
            GIT_BRANCH_NAME = "${gitBranchName}"
            GITEA_OWNER = "${giteaOwner}"
            REDMINE_API_BASE = "${redmineApiBase}"
            REDMINE_URL = "${redmineUrl}"
        }

        stages {
            stage('Webhook Info') {
                steps {
                    echo 'Webhook received'
                    echo "ref = ${env.ref}"
                    echo "after = ${env.after}"
                    echo "APP_NAME = ${env.APP_NAME}"
                }
            }

            stage('Redmine 이슈 ID 파싱') {
                steps {
                    script {
                        def prNumber = extractPrNumber(env.COMMIT_MESSAGE)
                        if (prNumber) {
                            echo "PR 번호: ${prNumber}"
                            withCredentials([string(credentialsId: 'redmine-api-key', variable: 'KEY')]) {
                                def response = httpRequest(
                                    httpMode: 'GET',
                                    url: "${env.REDMINE_URL}/issues.json?cf_1=${prNumber}&tracker_id=4&status_id=9",
                                    customHeaders: [[name: 'X-Redmine-API-Key', value: KEY]]
                                )
                                def json = readJSON text: response.content
                                if (json.issues?.size() > 0) {
                                    env.REDMINE_ISSUE_ID = json.issues[0].id.toString()
                                    echo "Redmine Issue ID: ${env.REDMINE_ISSUE_ID}"
                                } else {
                                    echo 'Redmine 이슈 못 찾음'
                                }
                            }
                        } else {
                            echo 'PR 번호 파싱 실패'
                        }
                    }
                }
            }

            stage('Checkout main') {
                steps {
                    dir('source') {
                        git(
                            branch: env.GIT_BRANCH_NAME,
                            url: "http://gitea-http:3000/${env.GITEA_OWNER}/${env.APP_NAME}.git",
                            credentialsId: 'gitea-credentials'
                        )
                    }
                }
            }

            stage('Load and Run') {
                steps {
                    script {
                        def core = load 'common/core.groovy'
                        core.transferSourceArchiveToFtc('source', env.GITEA_OWNER, env.APP_NAME)
                    }
                }
            }
        }

        post {
            success {
                script {
                    updateRedmineStatus(10, 'FTC 전송 완료')
                }
            }
            failure {
                script {
                    updateRedmineStatus(12, 'FTC 전송 실패')
                }
            }
        }
    }
}

def updateRedmineStatus(Integer statusId, String notes) {
    if (env.REDMINE_ISSUE_ID?.trim()) {
        withCredentials([string(credentialsId: 'redmine-api-key', variable: 'KEY')]) {
            httpRequest(
                httpMode: 'PUT',
                url: "${env.REDMINE_API_BASE}/issues/${env.REDMINE_ISSUE_ID}.json",
                customHeaders: [[name: 'X-Redmine-API-Key', value: KEY]],
                contentType: 'APPLICATION_JSON',
                requestBody: """{"issue": {"status_id": ${statusId}, "notes": "${notes}"}}"""
            )
        }
    }
}

@NonCPS
def extractPrNumber(String msg) {
    if (!msg) {
        return null
    }

    def matcher = msg =~ /\(#(\d+)\)/
    return matcher.find() ? matcher.group(1) : null
}

return this
