package com.shop.seckill.dao;

import com.shop.seckill.pojo.Sequence;

public interface SequenceMapper {
    /**
     * This method was generated by MyBatis Generator.
     * This method corresponds to the database table sequence_info
     *
     * @mbg.generated Sat Apr 23 21:01:39 CST 2022
     */
    int insert(Sequence record);

    /**
     * This method was generated by MyBatis Generator.
     * This method corresponds to the database table sequence_info
     *
     * @mbg.generated Sat Apr 23 21:01:39 CST 2022
     */
    int insertSelective(Sequence record);

    /**
     * This method was generated by MyBatis Generator.
     * This method corresponds to the database table sequence_info
     *
     * @mbg.generated Sat Apr 23 21:01:39 CST 2022
     */
    //getSequenceByName
    Sequence selectByPrimaryKey(String name);

    /**
     * This method was generated by MyBatis Generator.
     * This method corresponds to the database table sequence_info
     *
     * @mbg.generated Sat Apr 23 21:01:39 CST 2022
     */
    int updateByPrimaryKeySelective(Sequence record);

    /**
     * This method was generated by MyBatis Generator.
     * This method corresponds to the database table sequence_info
     *
     * @mbg.generated Sat Apr 23 21:01:39 CST 2022
     */
    int updateByPrimaryKey(Sequence record);
}