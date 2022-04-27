package com.shop.seckill.validator;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

import javax.validation.ConstraintViolation;
import javax.validation.Validation;
import javax.validation.Validator;
import java.util.Set;

@Component
public class ValidatorImpl implements InitializingBean {

    private Validator validator;

    //实现校验方法并返回校验结果
    public ValidationResult validate(Object bean) {
        final ValidationResult result = new ValidationResult();
        Set<ConstraintViolation<Object>> validateSet = validator.validate(bean);
        //有错误的属性字段
        if(validateSet.size() > 0) {
            result.setHasError(true);
            validateSet.forEach(constraintViolation->{
                //这里是JSR303校验的属性字段对应的错误message信息
                String errMsg = constraintViolation.getMessage();
                //这里是JSR303校验的属性字段
                String propertyName = constraintViolation.getPropertyPath().toString();
                //把校验错误的每个属性字段信息和错误信息放到 msgMap中
                result.getErrorMsgMap().put(propertyName, errMsg);
            });
        }
        return result;
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }
}
