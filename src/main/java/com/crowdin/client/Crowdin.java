package com.crowdin.client;

import com.crowdin.client.core.http.exceptions.HttpBadRequestException;
import com.crowdin.client.core.http.exceptions.HttpException;
import com.crowdin.client.core.model.*;
import com.crowdin.client.languages.model.Language;
import com.crowdin.client.sourcefiles.model.*;
import com.crowdin.client.translations.model.CrowdinTranslationCreateProjectBuildForm;
import com.crowdin.client.translations.model.ProjectBuild;
import com.crowdin.client.translations.model.UploadTranslationsRequest;
import com.crowdin.util.NotificationUtil;
import com.crowdin.util.RetryUtil;
import com.intellij.ide.plugins.PluginManager;
import com.intellij.openapi.extensions.PluginId;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.crowdin.Constants.MESSAGES_BUNDLE;

public class Crowdin {

    private static final String PLUGIN_ID = "com.crowdin.crowdin-idea";

    private final Long projectId;

    private final Project project;

    private final com.crowdin.client.Client client;

    public Crowdin(@NotNull Project project, @NotNull Long projectId, @NotNull String apiToken, String baseUrl) {
        this.project = project;
            this.projectId = projectId;
            Credentials credentials = new Credentials(apiToken, null, baseUrl);
            ClientConfig clientConfig = ClientConfig.builder()
                    .userAgent("crowdin-android-studio-plugin/ " + PluginManager.getPlugin(PluginId.getId(PLUGIN_ID)).getVersion() + " android-studio/" + PluginManager.getPlugin(PluginId.getId(PluginManager.CORE_PLUGIN_ID)).getVersion())
                    .build();
            this.client = new Client(credentials, clientConfig);
    }

    public Long addStorage(String fileName, InputStream content) {
        try {
            return this.client.getStorageApi()
                .addStorage(fileName, content)
                .getData()
                .getId();
        } catch (Exception e) {
            throw new RuntimeException(this.getErrorMessage(e));
        }
    }

    public com.crowdin.client.sourcefiles.model.File updateSource(Long sourceId, UpdateFileRequest request) {
        try {
            return this.client.getSourceFilesApi()
                .updateOrRestoreFile(this.projectId, sourceId, request)
                .getData();
        } catch (Exception e) {
            throw new RuntimeException(this.getErrorMessage(e));
        }
    }

    public com.crowdin.client.sourcefiles.model.File addSource(AddFileRequest request) {
        try {
            return this.client.getSourceFilesApi()
                .addFile(this.projectId, request)
                .getData();
        } catch (Exception e) {
            throw new RuntimeException(this.getErrorMessage(e));
        }
    }

    public void uploadTranslation(String languageId, UploadTranslationsRequest request) {
        try {
            this.client.getTranslationsApi()
                .uploadTranslations(this.projectId, languageId, request)
                .getData();
        } catch (Exception e) {
            throw new RuntimeException(this.getErrorMessage(e));
        }
    }

    public Directory addDirectory(AddDirectoryRequest request) {
        try {
            return this.client.getSourceFilesApi()
                .addDirectory(this.projectId, request)
                .getData();
        } catch (Exception e) {
            throw new RuntimeException(this.getErrorMessage(e));
        }
    }

    public com.crowdin.client.projectsgroups.model.Project getProject() {
        try {
            return this.client.getProjectsGroupsApi()
                .getProject(this.projectId)
                .getData();
        } catch (Exception e) {
            throw new RuntimeException(this.getErrorMessage(e));
        }
    }

    public List<Language> getProjectLanguages() {
        com.crowdin.client.projectsgroups.model.Project crowdinProject = this.getProject();
        return this.getSupportedLanguages()
            .stream()
            .filter(lang -> crowdinProject.getTargetLanguageIds().contains(lang.getId()))
            .collect(Collectors.toList());
    }

    public File downloadTranslations(VirtualFile baseDir, Long branchId) {
        try {
            CrowdinTranslationCreateProjectBuildForm buildProjectTranslationRequest = new CrowdinTranslationCreateProjectBuildForm();
            buildProjectTranslationRequest.setBranchId(branchId);
            ResponseObject<ProjectBuild> projectBuildResponseObject = this.client.getTranslationsApi().buildProjectTranslation(this.projectId, buildProjectTranslationRequest);
            Long buildId = projectBuildResponseObject.getData().getId();

            boolean finished = false;
            while (!finished) {
                ResponseObject<ProjectBuild> projectBuildStatusResponseObject = this.client.getTranslationsApi().checkBuildStatus(this.projectId, buildId);
                finished = projectBuildStatusResponseObject.getData().getStatus().equalsIgnoreCase("finished");
            }

            ResponseObject<DownloadLink> downloadLinkResponseObject = this.client.getTranslationsApi().downloadProjectTranslations(this.projectId, buildId);
            String link = downloadLinkResponseObject.getData().getUrl();

            File file = new File(baseDir.getCanonicalPath() + "/all.zip");
            try (ReadableByteChannel readableByteChannel = Channels.newChannel(new URL(link).openStream()); FileOutputStream fos = new FileOutputStream(file)) {
                fos.getChannel().transferFrom(readableByteChannel, 0, Long.MAX_VALUE);
            }
            return file;
        } catch (Exception e) {
            NotificationUtil.showErrorMessage(this.project, this.getErrorMessage(e));
            return null;
        }
    }

    public List<Language> getSupportedLanguages() {
        try {
            return client.getLanguagesApi().listSupportedLanguages(500, 0)
                .getData()
                .stream()
                .map(ResponseObject::getData)
                .collect(Collectors.toList());
        } catch (Exception e) {
            throw new RuntimeException(this.getErrorMessage(e));
        }
    }

    public Map<Long, Directory> getDirectories(Long branchId) {
        try {
            return executeRequestFullList((limit, offset) ->
                    this.client.getSourceFilesApi()
                        .listDirectories(this.projectId, branchId, null, true, limit, offset)
                        .getData()
                )
                .stream()
                .map(ResponseObject::getData)
                .filter(dir -> Objects.equals(dir.getBranchId(), branchId))
                .collect(Collectors.toMap(Directory::getId, Function.identity()));
        } catch (Exception e) {
            throw new RuntimeException(this.getErrorMessage(e));
        }
    }

    public List<com.crowdin.client.sourcefiles.model.File> getFiles(Long branchId) {
        try {
            return executeRequestFullList((limit, offset) ->
                    this.client.getSourceFilesApi()
                        .listFiles(this.projectId, branchId, null, true, 500, 0)
                        .getData()
                )
                .stream()
                .map(ResponseObject::getData)
                .filter(file -> Objects.equals(file.getBranchId(), branchId))
                .collect(Collectors.toList());
        } catch (Exception e) {
            throw new RuntimeException(this.getErrorMessage(e));
        }
    }

    /**
     * @param request represents function that downloads list of models and has two args (limit, offset)
     * @param <T> represents model
     * @return list of models accumulated from request function
     */
    private static <T> List<T> executeRequestFullList(BiFunction<Integer, Integer, List<T>> request) {
        List<T> models = new ArrayList<>();
        long counter;
        int limit = 500;
        do {
            List<T> responseModels = request.apply(limit, models.size());
            models.addAll(responseModels);
            counter = responseModels.size();
        } while (counter == limit);
        return models;
    }

    public Branch addBranch(AddBranchRequest request) {
        try {
            return this.client.getSourceFilesApi()
                .addBranch(this.projectId, request)
                .getData();
        } catch (Exception e) {
            String errorMessage = this.getErrorMessage(e);
            if (errorMessage.contains("regexNotMatch File name can't contain")) {
                throw new RuntimeException(MESSAGES_BUNDLE.getString("errors.branch_contains_forbidden_symbols"));
            } else {
                throw new RuntimeException(this.getErrorMessage(e));
            }
        }
    }

    public Optional<Branch> getBranch(String name) {
        List<ResponseObject<Branch>> branches = this.client.getSourceFilesApi().listBranches(this.projectId, name, 500, null).getData();
        return branches.stream()
                .filter(e -> e.getData().getName().equalsIgnoreCase(name))
                .map(ResponseObject::getData)
                .findFirst();
    }

    public Map<String, Branch> getBranches() {
        try {
            return executeRequestFullList((limit, offset) ->
                this.client.getSourceFilesApi()
                    .listBranches(this.projectId, null, limit, offset)
                    .getData()
            )
                .stream()
                .map(ResponseObject::getData)
                .collect(Collectors.toMap(Branch::getName, Function.identity()));
        } catch (Exception e) {
            throw new RuntimeException(this.getErrorMessage(e));
        }
    }

    private String getErrorMessage(Exception e) {
        if (e instanceof HttpException) {
            HttpException ex = (HttpException) e;
            if (ex.getError().getCode().equalsIgnoreCase("401")) {
                return MESSAGES_BUNDLE.getString("errors.authorize");
            } else {
                return ex.getError().getMessage();
            }
        } else if (e instanceof HttpBadRequestException) {
            return ((HttpBadRequestException) e).getErrors().stream()
                    .map(er -> {
                        String key = er.getError().getKey() == null ? "" : er.getError().getKey();
                        return key + " " + er.getError().getErrors().stream()
                                .map(err -> err.getCode() + " " + err.getMessage())
                                .collect(Collectors.joining(";"));
                    })
                    .collect(Collectors.joining(";"));
        } else {
            return e.getMessage();
        }
    }

    private boolean concurrentIssue(Exception error) {
        return this.codeExists(error, "notUnique") || this.codeExists(error, "parallelCreation");
    }

    private boolean codeExists(Exception e, String code) {
        if (e instanceof HttpException) {
            return ((HttpException) e).getError().getCode().equalsIgnoreCase(code);
        } else if (e instanceof HttpBadRequestException) {
            return ((HttpBadRequestException) e).getErrors().stream()
                    .anyMatch(error -> error.getError().getErrors().stream()
                            .anyMatch(er -> er.getCode().equalsIgnoreCase(code))
                    );
        } else {
            return false;
        }
    }

    private Long waitAndFindBranch(String name) throws Exception {
        return RetryUtil.retry(() -> {
            ResponseList<Branch> branchResponseList = this.client.getSourceFilesApi().listBranches(this.projectId, name, 500, null);
            ResponseObject<Branch> branchResponseObject = branchResponseList.getData().stream()
                    .filter(branch -> branch.getData().getName().equalsIgnoreCase(name))
                    .findFirst().orElse(null);
            if (branchResponseObject != null) {
                return branchResponseObject.getData().getId();
            } else {
                throw new Exception(String.format(MESSAGES_BUNDLE.getString("errors.find_branch"), name));
            }
        });
    }

    private boolean customMessage(Exception e) {
        if (e instanceof HttpException) {
            HttpException ex = (HttpException) e;
            if (ex.getError().getCode().equalsIgnoreCase("401")) {
                return true;
            }
        }
        return false;
    }

}
