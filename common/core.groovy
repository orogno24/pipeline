import groovy.json.JsonOutput

def createRedmineIssue(String status = "START") {
    withCredentials([string(credentialsId: 'redmine-api-key', variable: 'REDMINE_API_KEY')]) {

        def commitSha = sh(
            script: 'git rev-parse HEAD',
            returnStdout: true
        ).trim()

        def version = env.APP_VERSION ?: 'latest'

        def subject = "[Jenkins] ${env.JOB_NAME} - ${env.GIT_BRANCH} - ${version}"
        def description = """배포 상태: ${status}

브랜치: ${env.GIT_BRANCH}
태그: ${version}
커밋: ${commitSha}
빌드 번호: ${env.BUILD_NUMBER}
빌드 URL: ${env.BUILD_URL ?: ''}
"""

        writeFile file: 'redmine_issue.json', text: JsonOutput.toJson([
            issue: [
                project_id : 'jt-genie-lam',
                subject    : subject,
                description: description
            ]
        ])

        def response = sh(
            script: '''
                curl -sS -X POST "$REDMINE_URL/issues.json" \
                  -H "X-Redmine-API-Key: $REDMINE_API_KEY" \
                  -H "Content-Type: application/json" \
                  --data @redmine_issue.json
            ''',
            returnStdout: true
        ).trim()

        echo "Redmine create response: ${response}"

        writeFile file: 'redmine_create_response.json', text: response

        def issueId = sh(
            script: '''
                python3 - <<'PY'
import json
with open('redmine_create_response.json', 'r', encoding='utf-8') as f:
    data = json.load(f)
issue_id = data.get('issue', {}).get('id')
print(issue_id if issue_id is not None else "")
PY
            ''',
            returnStdout: true
        ).trim()

        if (!issueId) {
            error("Redmine 이슈 생성 실패 - issue id 추출 실패. response=${response}")
        }

        env.REDMINE_ISSUE_ID = issueId
        echo "Redmine issue created: ${env.REDMINE_ISSUE_ID}"
    }
}

def updateRedmineIssue(String note, Integer doneRatio = null) {
    if (!env.REDMINE_ISSUE_ID?.trim()) {
        echo "REDMINE_ISSUE_ID 가 없어 Redmine 업데이트를 건너뜁니다."
        return
    }

    withCredentials([string(credentialsId: 'redmine-api-key', variable: 'REDMINE_API_KEY')]) {
        def issueMap = [notes: note]
        if (doneRatio != null) {
            issueMap.done_ratio = doneRatio
        }

        writeFile file: 'redmine_update.json', text: JsonOutput.toJson([issue: issueMap])

        sh '''
            curl -sS -X PUT "$REDMINE_URL/issues/$REDMINE_ISSUE_ID.json" \
              -H "X-Redmine-API-Key: $REDMINE_API_KEY" \
              -H "Content-Type: application/json" \
              --data @redmine_update.json
        '''
    }
}

def markStage(String stageName, String result, Integer doneRatio) {
    def msg = """[${stageName}] ${result}
- Job: ${env.JOB_NAME}
- Build: #${env.BUILD_NUMBER}
- Branch: ${env.GIT_BRANCH}
- Version: ${env.APP_VERSION ?: 'unknown'}
- URL: ${env.BUILD_URL ?: ''}
"""
    updateRedmineIssue(msg, doneRatio)
}

def createOrUpdateStartIssue() {
    createRedmineIssue("START")

    if (!env.REDMINE_ISSUE_ID?.trim()) {
        echo "REDMINE_ISSUE_ID 생성 실패로 시작 업데이트를 건너뜁니다."
        return
    }

    updateRedmineIssue(
        """[Pipeline] 시작
- Job: ${env.JOB_NAME}
- Build: #${env.BUILD_NUMBER}
- Branch: ${env.GIT_BRANCH}
- Version: ${env.APP_VERSION ?: 'unknown'}
- URL: ${env.BUILD_URL ?: ''}
""",
        5
    )
}

def checkout() {
    stage('Git Checkout') {
        try {
            echo("===============[Git Checkout] 단계 시작===============")

            git(
                url: env.GIT_URL,
                branch: env.GIT_BRANCH,
                credentialsId: env.GIT_CREDENTIALS_ID
            )

            env.APP_VERSION = sh(
                script: 'git rev-parse --short HEAD',
                returnStdout: true
            ).trim()

            echo("Checked out branch: ${env.GIT_BRANCH}")
            echo("App Version: ${env.APP_VERSION}")
            echo("===============[Git Checkout] 단계 종료===============")
        } catch (err) {
            echo("[Git Checkout] 실패: ${err}")
            error("Git 체크아웃 단계에서 오류 발생")
        }
    }
}

def dockerBuild() {
    stage('Docker Build') {
        try {
            markStage('Docker Build', 'START', 30)

            echo("===============[Docker Build] 단계 시작===============")

            container('docker-dind') {
                sh '''
                    docker build -t "$DOCKER_REGISTRY_URL/$DOCKER_IMAGE_NAME:$APP_VERSION" .
                    docker tag "$DOCKER_REGISTRY_URL/$DOCKER_IMAGE_NAME:$APP_VERSION" "$DOCKER_REGISTRY_URL/$DOCKER_IMAGE_NAME:latest"
                '''
            }

            echo("===============[Docker Build] 단계 종료===============")
            markStage('Docker Build', 'SUCCESS', 60)
        } catch (err) {
            markStage('Docker Build', "FAIL - ${err}", 30)
            echo("Docker Build 실패: ${err.getMessage()}")
            error("Docker Build 단계에서 오류 발생")
        }
    }
}

def dockerPush() {
    stage('Docker Push') {
        try {
            markStage('Docker Push', 'START', 70)

            echo("===============[Docker Push] 단계 시작===============")

            container('docker-dind') {
                withCredentials([usernamePassword(
                    credentialsId: env.DOCKER_REGISTRY_CREDENTIALS_ID,
                    usernameVariable: 'HARBOR_USER',
                    passwordVariable: 'HARBOR_PASS'
                )]) {
                    sh '''
                        set -e
                        echo "$HARBOR_PASS" | docker login "$DOCKER_REGISTRY_URL" -u "$HARBOR_USER" --password-stdin
                        docker push "$DOCKER_REGISTRY_URL/$DOCKER_IMAGE_NAME:$APP_VERSION"
                    '''
                }
            }

            echo("===============[Docker Push] 단계 종료===============")
            markStage('Docker Push', 'SUCCESS', 85)
        } catch (err) {
            markStage('Docker Push', "FAIL - ${err}", 70)
            throw err
        }
    }
}

def gitOpsDeploy() {
    stage('GitOps Deploy') {
        try {
            markStage('GitOps Deploy', 'START', 90)

            echo("===============[GitOps Deploy] 단계 시작===============")

            git(
                url: env.GITOPS_URL,
                branch: env.GITOPS_BRANCH,
                credentialsId: env.GITOPS_CREDENTIALS_ID
            )

            dir(env.GITOPS_DIR) {
                withCredentials([usernamePassword(
                    credentialsId: env.GITOPS_CREDENTIALS_ID,
                    passwordVariable: 'GITOPS_PASSWORD',
                    usernameVariable: 'GITOPS_USERNAME'
                )]) {

                    def currentVersion = sh(
                        script: "grep 'newTag:' kustomization.yaml | awk -F ':' '{print \$2}' | head -n 1",
                        returnStdout: true
                    ).trim().replaceAll('"', '')

                    echo("현재 GitOps 이미지 태그: ${currentVersion}")
                    echo("배포할 이미지 태그(APP_VERSION): ${env.APP_VERSION}")

                    def featureBranch = "${env.GITOPS_CONTAINER_NAME}-${env.GIT_BRANCH}-${env.APP_VERSION}"

                    if (currentVersion != env.APP_VERSION) {
                        echo("이미지 태그가 달라 GitOps 업데이트를 진행합니다.")

                        sh """
                            git config --global user.email "jenkins@okestro.com"
                            git config --global user.name "jenkins"
                            git checkout -b ${featureBranch}

                            sed -i 's|newTag:.*|newTag: "${env.APP_VERSION}"|g' kustomization.yaml

                            git add kustomization.yaml
                            git commit -m "[${env.GIT_BRANCH}] Update image tag to ${env.APP_VERSION}"
                            git push http://${GITOPS_USERNAME}:${GITOPS_PASSWORD}@${GITOPS_PATH} ${featureBranch}
                        """

                        def prNumber = sh(
                            script: """
                                curl -s -u ${GITOPS_USERNAME}:${GITOPS_PASSWORD} \\
                                  -X POST "${env.GITOPS_API_URL}/pulls" \\
                                  -H "Content-Type: application/json" \\
                                  -d '{
                                    "head": "${featureBranch}",
                                    "base": "${env.GITOPS_BRANCH}",
                                    "title": "[${env.GIT_BRANCH}] Update image tag to ${env.APP_VERSION}",
                                    "body": "Auto-created by Jenkins webhook\\nBranch: ${env.GIT_BRANCH}\\nImage tag: ${env.APP_VERSION}"
                                  }' | sed -n 's/.*"number":[ ]*\\([0-9]*\\).*/\\1/p'
                            """,
                            returnStdout: true
                        ).trim()

                        updateRedmineIssue(
                            """[GitOps Deploy] PR 생성 완료
- PR 번호: ${prNumber}
- Feature Branch: ${featureBranch}
""",
                            95
                        )

                        def merged = false
                        def retryCnt = 0
                        while (!merged && retryCnt < 10) {
                            def merge = sh(
                                script: """
                                    curl -s -u ${GITOPS_USERNAME}:${GITOPS_PASSWORD} \\
                                      -X POST "${env.GITOPS_API_URL}/pulls/${prNumber}/merge" \\
                                      -H "Content-Type: application/json" \\
                                      -d '{
                                        "do": "merge",
                                        "merge_method": "merge",
                                        "commit_title": "[${env.GIT_BRANCH}] Auto-merged by Jenkins",
                                        "commit_message": "Update image tag to ${env.APP_VERSION}"
                                      }'
                                """,
                                returnStdout: true
                            ).trim()

                            echo("Merge response : ${merge}")
                            if (merge.contains("try")) {
                                retryCnt++
                                sleep 5
                            } else {
                                merged = true
                            }
                        }

                        if (merged) {
                            sleep 5
                            sh("git checkout ${env.GITOPS_BRANCH}")
                            sh("git push http://${GITOPS_USERNAME}:${GITOPS_PASSWORD}@${GITOPS_PATH} --delete ${featureBranch}")
                            echo("Gitea Merge Success, Feature Branch Deleted")
                        } else {
                            error("Gitea Merge Failed")
                        }
                    } else {
                        echo("현재 GitOps 태그와 동일하므로 업데이트하지 않습니다.")
                    }
                }
            }

            echo("===============[GitOps Deploy] 단계 종료===============")
            markStage('GitOps Deploy', 'SUCCESS', 100)
        } catch (err) {
            markStage('GitOps Deploy', "FAIL - ${err}", 90)
            throw err
        }
    }
}

return this