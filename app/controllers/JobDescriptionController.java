package controllers;

import awsWrappers.DynamoDbTableProvider;
import com.amazonaws.services.dynamodbv2.document.Item;
import com.amazonaws.services.dynamodbv2.document.ItemCollection;
import com.amazonaws.services.dynamodbv2.document.QueryOutcome;
import com.amazonaws.services.dynamodbv2.document.Table;
import com.amazonaws.services.dynamodbv2.document.spec.DeleteItemSpec;
import com.amazonaws.services.dynamodbv2.document.spec.QuerySpec;
import com.amazonaws.services.dynamodbv2.document.utils.NameMap;
import com.amazonaws.services.dynamodbv2.document.utils.ValueMap;
import com.amazonaws.services.dynamodbv2.model.ReturnValue;
import models.JobDescription;
import models.UserAccountDetails;
import models.UserKsas;
import play.data.FormFactory;
import play.mvc.Controller;
import play.mvc.Http;
import play.mvc.Result;
import utilities.DynamoAccessor;
import Enums.DynamoTables;
import utilities.KsaMatcher;

import javax.inject.Inject;
import java.util.*;
import java.util.stream.Collectors;

public class JobDescriptionController extends Controller {

    private FormFactory formFactory;
    private DynamoAccessor dynamoAccessor = DynamoAccessor.getInstance();

    @Inject
    public JobDescriptionController(FormFactory formFactory) {
        this.formFactory = formFactory;
    }

    public Result uploadJobApplication(Http.Request request) {
        return ok(views.html.recruiter.uploadJobDescription.render(views.html.ksaFormContent.render()));
    }

    public Result viewJobDescription(Http.Request request, String recruiter, String referenceCode) {
        JobDescription jobDescription = dynamoAccessor.getJobDescription(recruiter, referenceCode);
        return ok(views.html.recruiter.viewJobDescription.render(views.html.viewJobSpecificationBody.render(jobDescription),
                views.html.viewEmployeeSpecificationBody.render(jobDescription),
                request.cookie("userType").value(),
                DynamoAccessor.getInstance().doesUserHaveKsas(request.cookie("username").value())));
    }

    public Result getUploadedJobDescriptions(Http.Request request) {
        Table jobDescriptionsTable = DynamoDbTableProvider.getTable(DynamoTables.CAREER_SYNC_JOB_DESCRIPTIONS.getName());

        QuerySpec spec = new QuerySpec()
                .withKeyConditionExpression("recruiter = :recruiter")
                .withValueMap(new ValueMap()
                        .withString(":recruiter", request.cookie("username").value()));

        ItemCollection<QueryOutcome> items = jobDescriptionsTable.query(spec);

        Iterator<Item> iterator = items.iterator();
        List<JobDescription> jobDescriptions = new ArrayList<JobDescription>();
        Item item;
        while (iterator.hasNext()) {
            item = iterator.next();
            jobDescriptions.add(new JobDescription(item));
            System.out.println(item.toJSONPretty());
        }
        return ok(views.html.recruiter.uploadedJobDescriptions.render(jobDescriptions));
    }

    public Result submitJobDescription(Http.Request request) {
        JobDescription jobDescription = formFactory.form(JobDescription.class).bindFromRequest().get();
        jobDescription.setRecruiter(request.cookie("username").value());
        putJobDescriptionInTable(jobDescription);
        return redirect(routes.HomeController.index());
    }

    public Result editJobDescription(String recruiter, String referenceCode) {
        JobDescription jobDescription = dynamoAccessor.getJobDescription(recruiter, referenceCode);
        return ok(views.html.recruiter.editJobApplication.render(jobDescription, views.html.populatedKsaFormContent.render(jobDescription.getUserKsasFromJobDescription())));
    }

    public Result deleteJobDescription(Http.Request request, String referenceCode) {
        Table jobDescriptionsTable = DynamoDbTableProvider.getTable(DynamoTables.CAREER_SYNC_JOB_DESCRIPTIONS.getName());
        DeleteItemSpec deleteItemSpec = new DeleteItemSpec()
                .withPrimaryKey("recruiter", request.cookie("username").value())
                .withConditionExpression(":referenceCode = :val")
                .withNameMap(new NameMap()
                        .with(":referenceCode", "referenceCode"))
                .withValueMap(new ValueMap()
                        .withString(":val", referenceCode))
                .withReturnValues(ReturnValue.ALL_OLD);
        jobDescriptionsTable.deleteItem(deleteItemSpec);
        return getUploadedJobDescriptions(request);
    }

    public Result getPotentialCandidates(String recruiter, String referenceCode) {
        JobDescription jobDescription = DynamoAccessor.getInstance().getJobDescription(recruiter, referenceCode);
        List<UserAccountDetails> matchingCandidates = KsaMatcher.getInstance().getPotentialCandidates(recruiter, referenceCode);
        return ok(views.html.recruiter.matchingCandidates.render(referenceCode, jobDescription.getJobTitle(), matchingCandidates));
    }

    public Result getCandidateKsaProfile(String firstName, String surname, String username) {
        String fullName = firstName + " " + surname;
        UserKsas userKsas = DynamoAccessor.getInstance().getKsasForUser(username);
        return ok(views.html.candidateKsaProfile.render(fullName, userKsas));
    }

    public Result viewUserDetails(Http.Request request, String usernameToFindDetails) {
        UserAccountDetails userAccountDetails = DynamoAccessor.getInstance().getUserAccountDetails(usernameToFindDetails);
        Boolean userHasKsas = DynamoAccessor.getInstance().doesUserHaveKsas(request.cookie("username").value());
        return ok(views.html.candidate.userContactInformation.render(userAccountDetails, request.cookie("userType").value(), userHasKsas));
    }

    private void putJobDescriptionInTable(JobDescription jobDescription) {
        Optional<List<String>> communicaticationSkills = Optional.ofNullable(jobDescription.getCommunicationSkills());
        Optional<List<String>> peopleSkills = Optional.ofNullable(jobDescription.getPeopleSkills());
        Optional<List<String>> financialKnowledgeAndSkills = Optional.ofNullable(jobDescription.getFinancialKnowledgeAndSkills());
        Optional<List<String>> thinkingAndAnalysis = Optional.ofNullable(jobDescription.getThinkingAndAnalysis());
        Optional<List<String>> creativeOrInnovative = Optional.ofNullable(jobDescription.getCreativeOrInnovative());
        Optional<List<String>> administrativeOrOrganisational = Optional.ofNullable(jobDescription.getAdministrativeOrOrganisational());
        Item jobDescriptionItem = new Item()
                .withPrimaryKey("referenceCode", jobDescription.getReferenceCode())
                .with("recruiter", jobDescription.getRecruiter())
                .with("jobTitle", jobDescription.getJobTitle())
                .with("location", jobDescription.getLocation())
                .with("companyOrOrganisation", jobDescription.getCompanyOrOrganisation())
                .with("hours", jobDescription.getHours())
                .with("salary", jobDescription.getSalary())
                .with("mainPurposeOfJob", jobDescription.getMainPurposeOfJob())
                .with("mainResponsibilities", jobDescription.getMainResponsibilities())
                .with("qualificationLevel", jobDescription.getQualificationLevel())
                .with("qualificationArea", jobDescription.getQualificationArea())
                .withList("communicationSkills", convertSkillsToList(communicaticationSkills))
                .withList("peopleSkills", convertSkillsToList(peopleSkills))
                .withList("financialKnowledgeAndSkills", convertSkillsToList(financialKnowledgeAndSkills))
                .withList("thinkingAndAnalysis", convertSkillsToList(thinkingAndAnalysis))
                .withList("creativeOrInnovative", convertSkillsToList(creativeOrInnovative))
                .withList("administrativeOrOrganisational", convertSkillsToList(administrativeOrOrganisational));

        if (jobDescription.getDuration().isPresent()) {
            jobDescriptionItem.with("duration", jobDescription.getDuration().get());
        }

        if (jobDescription.getDepartment().isPresent()) {
            jobDescriptionItem.with("department", jobDescription.getDepartment().get());
        }

        if (jobDescription.getSection().isPresent()) {
            jobDescriptionItem.with("section", jobDescription.getSection().get());
        }

        if (jobDescription.getGrade().isPresent()) {
            jobDescriptionItem.with("grade", jobDescription.getGrade().get());
        }

        if (jobDescription.getReportsTo().isPresent()) {
            jobDescriptionItem.with("reportsTo", jobDescription.getReportsTo().get());
        }

        if (jobDescription.getResponsibleTo().isPresent()) {
            jobDescriptionItem.with("responsibleTo", jobDescription.getResponsibleTo().get());
        }

        if (jobDescription.getGeneral().isPresent()) {
            jobDescriptionItem.with("general", jobDescription.getGeneral().get());
        }

        DynamoDbTableProvider.getTable(DynamoTables.CAREER_SYNC_JOB_DESCRIPTIONS.getName()).putItem(jobDescriptionItem);
    }

    private List<String> convertSkillsToList(Optional<List<String>> skills) {
        return skills.map(strings -> strings.stream().filter(Objects::nonNull).collect(Collectors.toList())).orElse(Collections.EMPTY_LIST);
    }
}