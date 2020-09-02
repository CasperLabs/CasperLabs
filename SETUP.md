# Development Machine Setup for UBuntu 20.04

These steps will install all the necessary dependencies and tools needed for core development.  

### Protobuf

Pull latest ```protobuf-all-x.x.x.tar.gz``` from [here](https://github.com/protocolbuffers/protobuf/releases)
```
sudo apt-get install autoconf automake libtool curl make g++ unzip 
```

Inside the directory where protobuf is decompressed, run the following commands:

```
./autogen.sh
./configure
make
make check
sudo make install
sudo ldconfig
```

### Python

Python 3.8 is the lowest version of Python available by default in 20.04.  

#### Install pipenv

```
sudo apt install python3-pip 
python3.8 -m pip install pipenv
```

#### The easiest solution to get Python3.7 is using another ppa.   
If you plan on running the Python client, or STESTS, but not developing with Python, this configuration is enough.
```
sudo add-apt-repository ppa:deadsnakes/ppa
sudo apt-get update
sudo apt install python3.7 python3.7-dev python3.8-dev
```

If you are planning on using multiple versions of Python and possibly testing a new release version (3.9+), it is better to setup pyenv.
It's a better solution with more flexibility.

#### More complete Python versioning option with pyenv:
install pyenv

```
git clone https://github.com/pyenv/pyenv.git ~/.pyenv
echo 'export PYENV_ROOT="$HOME/.pyenv"' >> ~/.bash_profile
echo 'export PATH="$PYENV_ROOT/bin:$PATH"' >> ~/.bash_profile
echo 'export PYENV_ROOT="$HOME/.pyenv"' >> ~/.bashrc
echo 'export PATH="$PYENV_ROOT/bin:$PATH"' >> ~/.bashrc
```

Fix dependencies when installing and building python.

```
sudo apt install libreadline-dev libbz2-dev libsqlite3-dev libffi-dev
```

#### Installing Python 3.7 and 3.8
```
pyenv install 3.7.8
pyenv install 3.7-dev
pyenv install 3.8.3
pyenv install 3.8-dev
```

### Docker


```
sudo apt install git docker docker-compose
sudo usermod -aG docker $USER
```

You must log out and back in before working with docker to sync up the new group association.

#### Fix docker-compose issue:
```
sudo curl -L https://github.com/docker/compose/releases/download/1.21.2/docker-compose-`uname -s`-`uname -m` -o /usr/local/bin/docker-compose
sudo chmod +x /usr/local/bin/docker-compose
```

### SBT - Scala
```
sudo apt install default-jdk
echo "deb https://dl.bintray.com/sbt/debian /" | sudo tee -a /etc/apt/sources.list.d/sbt.list
curl -sL "https://keyserver.ubuntu.com/pks/lookup?op=get&search=0x2EE0EA64E40A89B84B2DF73499E82A75642AC823" | sudo apt-key add
sudo apt-get update
sudo apt-get install sbt
```

### Rust
```
curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh
```

Before running the following, you will have needed to run the command at the end of setup or reloaded your shell. You also need to add cargo to your $PATH as shown in a future section.
```
sudo apt install pkg-config libssl-dev
cargo install cargo-audit
```
### NPM and AssemblyScript

```
sudo apt update
sudo apt install curl dirmngr apt-transport-https lsb-release ca-certificates
curl -sL https://deb.nodesource.com/setup_12.x | sudo -E bash -
sudo apt install nodejs gcc g++ make
sudo npm install -g assemblyscript@0.10.0
```


### Update $PATH to find local bin directories

Added to ```.bashrc```
```
# set PATH so it includes user's private bin if it exists
if [ -d "$HOME/bin" ] ; then
    PATH="$HOME/bin:$PATH"
fi

# set PATH so it includes user's private bin if it exists
if [ -d "$HOME/.local/bin" ] ; then
    PATH="$HOME/.local/bin:$PATH"
fi

# set PATH so it includes rust bin
if [ -d "$HOME/.cargo/bin" ] ; then
    PATH="$HOME/.cargo/bin:$PATH"
fi

export PATH=$PATH
```

### Github

Setup ssh keys 
```
ssh-keygen -t rsa -b 4096 -C "[email addr]"
```
for signing commits in GitHub.
