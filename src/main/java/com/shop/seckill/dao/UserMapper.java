package com.shop.seckill.dao;

import com.shop.seckill.pojo.User;

public interface UserMapper {
    /**
     * This method was generated by MyBatis Generator.
     * This method corresponds to the database table user_info
     *
     * @mbg.generated Fri Apr 22 21:15:01 CST 2022
     */
    int insert(User record);

    /**
     * This method was generated by MyBatis Generator.
     * This method corresponds to the database table user_info
     * 插入部分用户属性
     * @mbg.generated Fri Apr 22 21:15:01 CST 2022
     */
    int insertSelective(User record);

    /**
     * This method was generated by MyBatis Generator.
     * This method corresponds to the database table user_info
     *
     * @mbg.generated Fri Apr 22 21:15:01 CST 2022
     */
    User selectByPrimaryKey(Integer id);

    User selectByTelphone(String telphone);

    /**
     * This method was generated by MyBatis Generator.
     * This method corresponds to the database table user_info
     * 更新部分用户属性
     * @mbg.generated Fri Apr 22 21:15:01 CST 2022
     */
    int updateByPrimaryKeySelective(User record);

    /**
     * This method was generated by MyBatis Generator.
     * This method corresponds to the database table user_info
     *
     * @mbg.generated Fri Apr 22 21:15:01 CST 2022
     */
    int updateByPrimaryKey(User record);
}