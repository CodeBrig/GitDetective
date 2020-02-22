eb.onopen = function () {
    console.log('Connected to event bus');
    triggerJobLogTimer(githubRepository);

    var canTriggerBuild = false;
    eb.send('GetTriggerInformation', {'github_repository': githubRepository},
        function (error, message) {
            if (error == null) {
                canTriggerBuild = message.body.can_build;
                if (canTriggerBuild) {
                    $('#trigger_build_button').removeClass('disabled');
                }
            } else {
                console.log(error.message);
                $('#latest_job_log').append(error.message);
            }
        });

    $(document).ready(function () {
        //buttons
        $('#trigger_build_button').click(function () {
            if (canTriggerBuild) {
                canTriggerBuild = false;
                eb.send('CreateJob', {'github_repository': githubRepository},
                    function (error, message) {
                        if (error == null) {
                            $('#latest_job_log').text('');
                            logPositionIndex = -1;
                            triggerJobLogTimer(githubRepository);
                        } else {
                            console.log(error.message);
                            $('#latest_job_log').append(error.message);
                        }
                    });
                $('#trigger_build_button').addClass('disabled');
            }
        });
    });

    function triggerJobLogTimer(githubRepository) {
        //immediate run
        eb.send('GetLatestJobLog', {'github_repository': githubRepository},
            function (error, message) {
                if (error == null) {
                    displayLatestJobLog(message.body);
                } else {
                    console.log(error.message);
                    $('#latest_job_log').append(error.message);
                }
            });

        //timer
        setInterval(function () {
            console.log('Started job log timer');
            eb.send('GetLatestJobLog', {'github_repository': githubRepository},
                function (error, message) {
                    if (error == null) {
                        displayLatestJobLog(message.body);
                    } else {
                        console.log(error.message);
                        $('#latest_job_log').append(error.message);
                    }
                });
        }, 7500);
    }

    function displayLatestJobLog(logs) {
        console.log('Displaying latest job logs');
        if (logs.job_id !== latestJobLogId) {
            $('#latest_job_log').text('');
            logPositionIndex = -1;
            latestJobLogId = logs.job_id;
        }

        for (var i = 0; i < logs.logs.length; i++) {
            if (logPositionIndex < i) {
                $('#latest_job_log').append(logs.logs[i] + '<br>');
                logPositionIndex = i;
            }
        }
    }
};

function displayFunctionReference(functionReference) {
    $('#referenced_functions_breadcrumbs').append(
        '<li id="most_referenced_functions_breadcrumb" class="breadcrumb-item active">' +
        '<a>Function [id: ' + functionReference.id + ']</a></li>');
    $('#most_referenced_functions_breadcrumb').removeClass('active');
    $('#most_referenced_functions_breadcrumb_link').attr('href', '#');

    $('#references_main_title').html('<small class="text-muted"><b>Referenced function</b>: ' +
        functionReference.class_name + '.'
        + functionReference.function_signature.toHtmlEntities() + '</small>');
    $('#most_referenced_functions_table').hide();
    $('#function_references_table').show();

    if (functionReference.external_reference_count < 10) {
        $('#display_reference_amount_information').html('<b>Displaying ' + functionReference.external_reference_count + ' of ' +
            functionReference.external_reference_count + '</b>');
    } else {
        $('#display_reference_amount_information').html('<b>Displaying 10 of ' + functionReference.external_reference_count + '</b>');
    }

    eb.send('GetFunctionExternalReferences', {
        'function_id': functionReference.id,
        'offset': 0
    }, function (error, message) {
        if (error == null) {
            $('#function_references').html('');
            var mostReferencedFunctions = message.body;
            for (var i = 0; i < mostReferencedFunctions.length; i++) {
                var functionOrFile = mostReferencedFunctions[i];
                var codeLocation = 'https://github.com/' +
                    functionOrFile.projectName.substring(functionOrFile.projectName.indexOf(":") + 1) +
                    '/blob/' + functionOrFile.commitSha1 + '/' + functionOrFile.fileLocation + '#L' + functionOrFile.lineNumber;

                //table entry
                var rowHtml = '<tr>';
                if (functionOrFile.isFunction) {
                    rowHtml += '<td><h6>' + functionOrFile.shortClassName +
                        '</h6> <div style="max-width: 450px; word-wrap:break-word;" class="text-muted">'
                        + functionOrFile.shortFunctionSignature.toHtmlEntities() + '</div></td>';
                    rowHtml += '<td><a href="' + gitdetectiveUrl +
                        functionOrFile.projectName.substring(functionOrFile.projectName.indexOf(":") + 1) + '">' +
                        '<button type="button" class="btn waves-effect waves-light btn-outline-primary">' +
                        functionOrFile.projectName.substring(functionOrFile.projectName.indexOf(":") + 1) +
                        '</button></a></td>';
                    rowHtml += '<td><a target="_blank" href="' + codeLocation + '">' +
                        '<button type="button" class="btn waves-effect waves-light btn-outline-primary">Code location</button>' +
                        '</a></td>';
                } else {
                    rowHtml += '<td><h6>' + functionOrFile.shortClassName +
                        '</h6> <div style="max-width: 450px; word-wrap:break-word;" class="text-muted">'
                        + functionOrFile.fileLocation + '</div></td>';
                    rowHtml += '<td><button onclick=\'location.href="' + gitdetectiveUrl +
                        functionOrFile.projectName.substring(functionOrFile.projectName.indexOf(":") + 1) +
                        '";\' type="button" class="btn waves-effect waves-light btn-outline-primary">' +
                        functionOrFile.projectName.substring(functionOrFile.projectName.indexOf(":") + 1) +
                        '</button></td>';
                    rowHtml += '<td><a target="_blank" href="' + codeLocation + '">' +
                        '<button type="button" class="btn waves-effect waves-light btn-outline-primary">Code location</button>' +
                        '</a></td>';
                }
                rowHtml += '</tr>';
                $('#function_references').append(rowHtml);
            }

            $('#display_reference_amount_information').show();
        } else {
            console.log(error.message);
            $('#latest_job_log').append(error.message);
        }
    });
}

function displayMostReferencedFunctions() {
    $('#references_main_title').html('');
    $('#most_referenced_functions_table').show();
    $('#function_references_table').hide();
    $('#most_referenced_functions_breadcrumb').addClass('active');
    $('#most_referenced_functions_breadcrumb_link').removeAttr('href');
    $('#display_reference_amount_information').hide();

    var listItems = $('#referenced_functions_breadcrumbs li');
    if (listItems.length > 1) {
        listItems[listItems.length - 1].remove();
    }
}
