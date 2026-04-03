stage('Gitea PR Merge') {
            when {
                expression { env.PROCEED_PIPELINE == 'true' }
            }
            steps {
                script {
                    // httpRequest 실행
                    def response = httpRequest(
                        httpMode: 'POST',
                        url: "${env.GITEA_API_BASE}/repos/${env.GITEA_OWNER}/${env.REPO_NAME}/pulls/${env.PR_NUMBER}/merge",
                        authentication: 'gitea-credentials',
                        contentType: 'APPLICATION_JSON',
                        requestBody: """
                        {
                          "Do": "merge",
                          "merge_message_field": "Auto merged by Jenkins (#${env.ISSUE_ID})"
                        }
                        """,
                        validResponseCodes: '200:409' // 409를 허용하여 스크립트가 중단되지 않게 함
                    )

                    echo "Gitea Merge 응답 코드: ${response.status}"

                    if (response.status == 200) {
                        env.MERGE_RESULT = 'SUCCESS'
                        env.MERGE_NOTES = "✅ 자동 머지가 완료되었습니다."
                    } else if (response.status == 409) {
                        env.MERGE_RESULT = 'CONFLICT' // 409 전용 결과값 설정
                        env.MERGE_NOTES = "❌ 머지 실패: 코드 충돌(Conflict)이 발생했거나 Gitea 정책에 의해 머지할 수 없는 상태입니다. PR 페이지를 확인하세요."
                        echo "[WARNING] 409 Conflict 발생"
                    } else {
                        env.MERGE_RESULT = 'FAIL'
                        env.MERGE_NOTES = "🚫 머지 실패: 알 수 없는 에러가 발생했습니다. (Status: ${response.status})"
                    }
                }
            }
        }

        stage('Redmine 상태 업데이트') {
            when {
                expression { env.PROCEED_PIPELINE == 'true' }
            }
            steps {
                script {
                    withCredentials([string(credentialsId: 'redmine-api-key', variable: 'KEY')]) {
                        
                        // 결과에 따른 Redmine 상태 ID 결정
                        def targetStatusId = ""
                        if (env.MERGE_RESULT == 'SUCCESS') {
                            targetStatusId = env.MERGED_STATUS_ID
                        } else {
                            // 409를 포함한 모든 실패 케이스는 FAIL 상태로 처리
                            targetStatusId = env.MERGE_FAIL_STATUS_ID
                        }

                        httpRequest(
                            httpMode: 'PUT',
                            url: "${env.REDMINE_API_BASE}/issues/${env.ISSUE_ID}.json",
                            customHeaders: [[name: 'X-Redmine-API-Key', value: KEY]],
                            contentType: 'APPLICATION_JSON',
                            requestBody: """
                            {
                              "issue": {
                                "status_id": ${targetStatusId},
                                "notes": "${env.MERGE_NOTES}"
                              }
                            }
                            """
                        )
                        
                        // 만약 머지가 실패했다면 Jenkins 빌드 결과도 실패로 표시하고 싶을 때 (선택 사항)
                        if (env.MERGE_RESULT != 'SUCCESS') {
                            currentBuild.result = 'FAILURE'
                        }
                    }
                }
            }
        }