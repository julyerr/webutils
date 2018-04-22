package com.julyerr.mybatis;

import com.julyerr.mybatis.mapper.UserMapper;
import com.julyerr.mybatis.model.User;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;

public class GeneratorTest {
    private SqlSessionFactory sqlSessionFactory ;

    @Before
    public void setUp() throws IOException {
        String resource = "sqlMapConfig.xml";

//        读取配置文件
        InputStream inputStream = Resources.getResourceAsStream(resource);
//        根据配置文件生成sqlSessionFactory
        sqlSessionFactory = new SqlSessionFactoryBuilder().build(inputStream);
    }

    @Test
    public void testInsertUser(){
        SqlSession sqlSession = sqlSessionFactory.openSession();
        UserMapper userMapper = sqlSession.getMapper(UserMapper.class);

        User user = new User();
        user.setId(501);
        user.setUsername("test");
        userMapper.insert(user);
        sqlSession.commit();
    }

    @Test
    public void testSelectUser(){
        SqlSession sqlSession = sqlSessionFactory.openSession();
        UserMapper userMapper = sqlSession.getMapper(UserMapper.class);

        User user = userMapper.selectByPrimaryKey(1);
        System.out.println(user);
    }

}
