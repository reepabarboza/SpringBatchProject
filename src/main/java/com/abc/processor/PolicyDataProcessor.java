package com.abc.processor;

import java.math.BigDecimal;
import java.text.ParseException;
import java.util.Date;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.abc.constant.Constants;
import com.abc.constant.ErrorMessages;
import com.abc.constant.ProcessingMessages;
import com.abc.entity.PlanCoverage;
import com.abc.entity.PlanDescription;
import com.abc.entity.PolicyData;
import com.abc.entity.PolicyTransaction;
import com.abc.repository.PlanCoverageRepository;
import com.abc.repository.PlanDescriptionRepository;
import com.abc.repository.PolicyDataRepository;
import com.abc.util.DateUtil;


@Component
public class PolicyDataProcessor implements ItemProcessor<PolicyTransaction, PolicyTransaction> {
	
	private static final Logger log = LoggerFactory.getLogger(Constants.APPLICATION_LOG_FILE_NAME);
	
	@Autowired
	private PolicyDataRepository policyDataRepository;
	@Autowired
	private PlanDescriptionRepository planDescriptionRepository;
	@Autowired
	private PlanCoverageRepository planCoverageRepository;

	
	@Override
    public PolicyTransaction process(PolicyTransaction policyTransaction) throws Exception {
      
		processPolicy(policyTransaction);
    	
    	log.info("Final policyTransaction :: " + policyTransaction);

    	return policyTransaction;
    }
	
	/**
	 * Based on input details, find the right policy plan and process to calculate info such as amount paid by poilcy holder,
	 * amount paid by plan, error information
	 * @param policyTransaction
	 * @throws ParseException
	 */
	private void processPolicy(PolicyTransaction policyTransaction) throws ParseException {
		
		PolicyData policyData = policyDataRepository.findByPolicyIdAndPolicyHolderId(policyTransaction.getPolicyId(), policyTransaction.getPolicyHolderId());
		
		log.info("policyData :: " + policyData);
		
		Date policyDateOfService = DateUtil.formatDate(policyTransaction.getDateOfService());
		
		
		if(policyData == null) {
			
			log.info("No policy found for the given policy Id and policy Holder Id");
			policyTransaction.setErrorCode(ErrorMessages.E0001.toString());
			policyTransaction.setErrorMessage(ErrorMessages.E0001.getValue());
			
		} else if(policyData.getCoverageEndDate() != null && policyDateOfService.after(policyData.getCoverageEndDate())) {
			
			log.info("Date of service is after the coverage end date");
			policyTransaction.setErrorCode(ErrorMessages.E0002.toString());
			policyTransaction.setErrorMessage(ErrorMessages.E0002.getValue());
			
		} else {
			
			String planId = policyData.getPlanId();
			
			PlanCoverage planCoverage = planCoverageRepository.findByMainCategoryIgnoreCaseAndSubCategoryIgnoreCaseAndPlanId(
					policyTransaction.getCoverageMainCategory(), policyTransaction.getCoverageSubCategory(), planId);
			
			if(planCoverage == null) {
				
				log.info("No plan coverage found for the main and sub category");
				policyTransaction.setErrorCode(ErrorMessages.E0003.toString());
				policyTransaction.setErrorMessage(ErrorMessages.E0003.getValue());
				
			} else {
				
				log.info("Plan coverage found");
				policyTransaction.setDeductibleRule(planCoverage.getDeductibleRule());
				policyTransaction.setDeductiblePercentage(planCoverage.getDeductiblePercentage());
				
				BigDecimal planAmt = new BigDecimal(0);
				BigDecimal deductiblePercentage = policyTransaction.getDeductiblePercentage();
				BigDecimal deductibleAmt = planCoverage.getDeductibleAmt();
				
				PlanDescription planDescription = planDescriptionRepository.findByPlanId(policyData.getPlanId());
				
				
				if(deductibleAmt != null && deductibleAmt.compareTo(BigDecimal.ZERO) != 0) {
					
					calculateDeductible(policyTransaction, policyData, deductibleAmt);
					policyTransaction.setProcessingMessage(ProcessingMessages.POLICY_PAID.concat("$").concat(deductibleAmt.toString()));
					policyTransaction.setDeductibleRule(planCoverage.getDeductibleRule());
					
				} else if(planDescription.getAnnualDeductibleIndividual() != null && policyData.getIndividualAccumulatedDed().compareTo(planDescription.getAnnualDeductibleIndividual()) >= 0) {
					
					planAmt = deductiblePercentage.multiply(policyTransaction.getBilledAmount()).divide(new BigDecimal(100));
					calculateDeductible(policyTransaction, policyData, planAmt);
					policyTransaction.setProcessingMessage(ProcessingMessages.INDIVIDUAL_PLAN_MET);
					
				} else if(planDescription.getAnnualDeductibleFamily() != null && policyData.getFamilyAccumulatedDed().compareTo(planDescription.getAnnualDeductibleFamily()) >= 0) {
					
					planAmt = deductiblePercentage.multiply(policyTransaction.getBilledAmount()).divide(new BigDecimal(100));
					calculateDeductible(policyTransaction, policyData, planAmt);
					policyTransaction.setProcessingMessage(ProcessingMessages.FAMILY_PLAN_MET);
					
				} else {
					
					planAmt = new BigDecimal(0);
					calculateDeductible(policyTransaction, policyData, planAmt);
					policyTransaction.setProcessingMessage(ProcessingMessages.INDIVIDUAL_FAMILY_PLAN_NOT_MET);
					
				}
			}
		}
	}

	
	
	/**
	 * Calculates and updates Individual and Family deductibles
	 * @param policyTransaction
	 * @param policyData
	 * @param planAmt
	 */
	private void calculateDeductible(PolicyTransaction policyTransaction, PolicyData policyData, BigDecimal planAmt) {
		BigDecimal policyHolderAmt = policyTransaction.getBilledAmount().subtract(planAmt);
		BigDecimal totalIndividualAccumulatedDed = new BigDecimal(0);
		BigDecimal totalFamilyAccumulatedDed = new BigDecimal(0);
		
		policyTransaction.setPlanAmt(planAmt);
		policyTransaction.setPolicyHolderAmt(policyHolderAmt);
		
		totalIndividualAccumulatedDed = policyData.getIndividualAccumulatedDed().add(policyHolderAmt);
		totalFamilyAccumulatedDed = policyData.getFamilyAccumulatedDed().add(policyHolderAmt);
		
		log.info(" totalIndividualAccumulatedDedAmt :: " + totalIndividualAccumulatedDed);
		log.info(" totalFamilyAccumulatedDed :: " + totalFamilyAccumulatedDed);
		
		policyTransaction.setIndividualAccumulatedDed(totalIndividualAccumulatedDed);
		policyTransaction.setFamilyAccumulatedDed(totalFamilyAccumulatedDed);
		
		policyDataRepository.setIndividualDeductibleAmount(totalIndividualAccumulatedDed, policyData.getPolicyId(), policyData.getPolicyHolderId());
		policyDataRepository.setFamilyDeductibleAmount(totalFamilyAccumulatedDed, policyData.getPolicyId());
	}

}
